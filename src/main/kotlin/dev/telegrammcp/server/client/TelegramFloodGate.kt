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
 * One instance exists per account (one per [TdLibClientService]). Waits are
 * scoped to the target chat when the TDLib function has a `chatId` field, and
 * to the TDLib function otherwise, because Telegram's 429s are typically per
 * chat or per method: a flood wait in one chat must not block other chats.
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
        val seconds = flood.retryAfterSeconds?.takeIf { it > 0 } ?: return
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

        private val chatIdFields = ConcurrentHashMap<Class<*>, java.util.Optional<Field>>()

        fun chatScope(chatId: Long): String = "chat $chatId"

        fun methodScope(function: TdApi.Function<*>): String = "method ${function.javaClass.simpleName}"

        /** The gate scope for a TDLib request: its target chat if it has one, otherwise the method. */
        fun scopeOf(function: TdApi.Function<*>): String =
            chatIdOf(function)?.let(::chatScope) ?: methodScope(function)

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
