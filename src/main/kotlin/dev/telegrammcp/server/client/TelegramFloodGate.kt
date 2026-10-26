package dev.telegrammcp.server.client

import dev.telegrammcp.server.exception.TelegramApiException
import dev.telegrammcp.server.exception.TelegramFloodGateException
import it.tdlight.jni.TdApi
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Fails fast while a Telegram flood wait is active, instead of letting TDLib
 * queue (and Telegram extend) the wait.
 *
 * One instance exists per account (one per [TdLibClientService]), so every
 * scope is implicitly per account. Scopes (see [scopeOf]):
 * - writes are keyed by TDLib method plus target chat ("method SendMessage
 *   chat 42"): a flood on one kind of write in one chat blocks neither other
 *   chats nor other operations in the same chat;
 * - reads on the [TelegramReadRequests] allowlist are keyed by method across
 *   the account ("method GetChatHistory"): read limits are account-wide, and a
 *   write's flood must never block reads such as GetChatHistory or GetMessage;
 * - writes without a chat are keyed by method.
 *
 * A wait of "retry after 0" is gated (and reported) as [MIN_WAIT_SECONDS], see
 * [dev.telegrammcp.server.exception.TelegramErrorClassifier.parseRetryAfterSeconds].
 * All operations are safe for concurrent use: entries are merged by maximum and
 * expired entries are removed only if unchanged.
 */
class TelegramFloodGate(
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val blockedUntil = ConcurrentHashMap<String, Long>()

    /** Throws [TelegramFloodGateException] if [scope] is still inside a flood wait. */
    fun check(scope: String) {
        val until = blockedUntil[scope] ?: return
        val remainingNanos = until - nanoTime()
        if (remainingNanos <= 0) {
            blockedUntil.remove(scope, until)
            return
        }
        val seconds = TimeUnit.NANOSECONDS.toSeconds(remainingNanos) +
            if (remainingNanos % TimeUnit.SECONDS.toNanos(1) == 0L) 0 else 1
        throw TelegramFloodGateException(scope, seconds)
    }

    /** Remembers a flood wait returned by Telegram for [scope]. Other errors are ignored. */
    fun record(scope: String, error: Throwable) {
        val flood = error as? TelegramApiException ?: return
        if (!flood.isFloodWait) return
        val seconds = flood.retryAfterSeconds?.coerceAtLeast(MIN_WAIT_SECONDS) ?: return
        val now = nanoTime()
        blockedUntil.entries.removeIf { it.value - now <= 0 }
        val until = now + TimeUnit.SECONDS.toNanos(seconds.coerceAtMost(MAX_WAIT_SECONDS))
        blockedUntil.merge(scope, until) { old, new -> maxOf(old, new) }
    }

    fun activeScopes(): Set<String> {
        val now = nanoTime()
        return blockedUntil.filterValues { it - now > 0 }.keys
    }

    companion object {
        /** TDLib clamps waits to 14 days (NetQueryDelayer). */
        private const val MAX_WAIT_SECONDS = 14L * 24 * 60 * 60

        /** Shortest gate; matches the minimum delay TDLib itself applies (NetQueryDelayer clamps to 1 s). */
        const val MIN_WAIT_SECONDS = 1L

        private val chatIdFields = ConcurrentHashMap<Class<*>, java.util.Optional<Field>>()

        fun methodScope(function: TdApi.Function<*>): String = "method ${function.javaClass.simpleName}"

        /**
         * The gate scope for a TDLib request: the method for allow-listed reads,
         * method plus target chat for writes that have a chat, the method otherwise.
         */
        fun scopeOf(function: TdApi.Function<*>): String {
            val method = methodScope(function)
            if (TelegramReadRequests.isSafeRead(function)) return method
            val chatId = chatIdOf(function) ?: return method
            return "$method chat $chatId"
        }

        private fun chatIdOf(function: TdApi.Function<*>): Long? {
            val field = chatIdFields.computeIfAbsent(function.javaClass) { type ->
                java.util.Optional.ofNullable(
                    runCatching { type.getField("chatId") }.getOrNull()
                        ?.takeIf { it.type == Long::class.javaPrimitiveType && !Modifier.isStatic(it.modifiers) },
                )
            }
            return field.orElse(null)?.getLong(function)?.takeIf { it != 0L }
        }
    }
}
