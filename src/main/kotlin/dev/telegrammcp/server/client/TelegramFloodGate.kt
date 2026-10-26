package dev.telegrammcp.server.client

import dev.telegrammcp.server.exception.TelegramApiException
import dev.telegrammcp.server.exception.TelegramFloodGateException
import it.tdlight.jni.TdApi
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Fails fast while a Telegram flood wait is active, instead of letting TDLib
 * queue (and Telegram extend) the wait.
 *
 * One instance exists per account (one per [TdLibClientService]), so every
 * scope is implicitly per account. Scopes (see [scopeOf]):
 * - every kind of message send to a chat shares one scope ("send chat 42"):
 *   slow mode and per-chat send limits apply to SendMessage, SendMessageAlbum,
 *   ForwardMessages and the other send functions alike;
 * - account-wide writes such as joining chats or adding members are keyed by
 *   method only ("method JoinChat"): Telegram limits them per account, not per
 *   target chat;
 * - DownloadFile is keyed by file ("method DownloadFile file 7");
 * - other writes are keyed by method plus target chat ("method PinChatMessage
 *   chat 42"), or by method when they have no chat;
 * - reads on the [TelegramReadRequests] allowlist are keyed by method across
 *   the account ("method GetChatHistory"): read limits are account-wide, and a
 *   write's flood must never block reads such as GetChatHistory or GetMessage.
 *
 * Besides Telegram flood waits, [recordLocalTimeout] gates a scope briefly
 * after a request got no answer in time: TDLib may still hold it in its
 * flood-wait delayer, and further requests would queue behind it.
 *
 * A wait of "retry after 0" is gated (and reported) as [MIN_WAIT_SECONDS], see
 * [dev.telegrammcp.server.exception.TelegramErrorClassifier.parseRetryAfterSeconds].
 * All operations are safe for concurrent use: entries are merged by maximum and
 * expired entries are removed only if unchanged.
 */
class TelegramFloodGate(
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private data class Entry(val untilNanos: Long, val afterLocalTimeout: Boolean)

    private val blockedUntil = ConcurrentHashMap<String, Entry>()

    /** Throws [TelegramFloodGateException] if [scope] is still inside a flood wait. */
    fun check(scope: String) {
        val entry = blockedUntil[scope] ?: return
        val remainingNanos = entry.untilNanos - nanoTime()
        if (remainingNanos <= 0) {
            blockedUntil.remove(scope, entry)
            return
        }
        throw TelegramFloodGateException(scope, roundUpSeconds(remainingNanos), entry.afterLocalTimeout)
    }

    /** Whole seconds (rounded up) left in [scope]'s gate, or null when it is open. */
    fun remainingSeconds(scope: String): Long? {
        val remainingNanos = (blockedUntil[scope] ?: return null).untilNanos - nanoTime()
        return if (remainingNanos > 0) roundUpSeconds(remainingNanos) else null
    }

    private fun roundUpSeconds(nanos: Long): Long =
        TimeUnit.NANOSECONDS.toSeconds(nanos) + if (nanos % TimeUnit.SECONDS.toNanos(1) == 0L) 0 else 1

    /** Remembers a flood wait returned by Telegram for [scope]. Other errors are ignored. */
    fun record(scope: String, error: Throwable) {
        val flood = error as? TelegramApiException ?: return
        if (!flood.isFloodWait) return
        val seconds = flood.retryAfterSeconds?.coerceAtLeast(MIN_WAIT_SECONDS) ?: return
        block(scope, TimeUnit.SECONDS.toNanos(seconds.coerceAtMost(MAX_WAIT_SECONDS)), afterLocalTimeout = false)
    }

    /**
     * Gates [scope] for [duration] after a request in it got no TDLib answer in
     * time. Never shortens a longer flood wait already recorded for the scope.
     */
    fun recordLocalTimeout(scope: String, duration: Duration) {
        if (duration.isZero || duration.isNegative) return
        block(scope, duration.toNanos(), afterLocalTimeout = true)
    }

    private fun block(scope: String, nanos: Long, afterLocalTimeout: Boolean) {
        val now = nanoTime()
        blockedUntil.entries.removeIf { it.value.untilNanos - now <= 0 }
        val entry = Entry(now + nanos, afterLocalTimeout)
        blockedUntil.merge(scope, entry) { old, new -> if (new.untilNanos - old.untilNanos > 0) new else old }
    }

    fun activeScopes(): Set<String> {
        val now = nanoTime()
        return blockedUntil.filterValues { it.untilNanos - now > 0 }.keys
    }

    companion object {
        /** TDLib clamps waits to 14 days (NetQueryDelayer). */
        private const val MAX_WAIT_SECONDS = 14L * 24 * 60 * 60

        /** Shortest gate; matches the minimum delay TDLib itself applies (NetQueryDelayer clamps to 1 s). */
        const val MIN_WAIT_SECONDS = 1L

        /** Message-sending functions; they share one per-chat scope because slow mode covers all of them. */
        private val SEND_FUNCTIONS: Set<Class<out TdApi.Function<*>>> = setOf(
            TdApi.SendMessage::class.java,
            TdApi.SendMessageAlbum::class.java,
            TdApi.ForwardMessages::class.java,
            TdApi.ResendMessages::class.java,
            TdApi.SendInlineQueryResultMessage::class.java,
            TdApi.SendBotStartMessage::class.java,
        )

        /** Writes Telegram limits per account rather than per target chat (joins, invites, group creation). */
        private val ACCOUNT_WIDE_WRITES: Set<Class<out TdApi.Function<*>>> = setOf(
            TdApi.JoinChat::class.java,
            TdApi.JoinChatByInviteLink::class.java,
            TdApi.AddChatMember::class.java,
            TdApi.AddChatMembers::class.java,
            TdApi.CreateNewBasicGroupChat::class.java,
            TdApi.CreateNewSupergroupChat::class.java,
            TdApi.AddContact::class.java,
            TdApi.ImportContacts::class.java,
        )

        private val chatIdFields = ConcurrentHashMap<Class<*>, java.util.Optional<Field>>()

        fun methodScope(function: TdApi.Function<*>): String = "method ${function.javaClass.simpleName}"

        /** Shared scope of every message send to [chatId]. */
        fun sendScope(chatId: Long): String = "send chat $chatId"

        /** The gate scope for a TDLib request; see the class documentation. */
        fun scopeOf(function: TdApi.Function<*>): String {
            val method = methodScope(function)
            if (function is TdApi.DownloadFile) return "$method file ${function.fileId}"
            if (function.javaClass in SEND_FUNCTIONS) return chatIdOf(function)?.let(::sendScope) ?: method
            if (function.javaClass in ACCOUNT_WIDE_WRITES) return method
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
