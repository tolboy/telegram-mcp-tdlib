package dev.telegrammcp.server.security

import java.util.concurrent.ConcurrentHashMap

/**
 * Chats that carry credentials or account controls and are blocked by default.
 *
 * - `777000` is Telegram's own service chat. Login codes, new-session alerts
 *   and QR-login confirmations arrive there, so reading it is enough to sign
 *   in to the account from another device.
 * - `489000` is the Telegram Gateway chat for third-party verification codes.
 * - `@BotFather` returns bot tokens in plain text.
 * - `@SpamBot` handles account limits and appeals for the whole account.
 *
 * The block applies to every tool and resource, even under an all-chats
 * grant. An operator lifts it only explicitly: `TELEGRAM_ALLOW_SENSITIVE_CHATS`,
 * a permission grant with `allow_sensitive_chats`, or by naming the chat ID in
 * a static allow-list or a grant's `chat_ids` (see docs/PERMISSIONS.md).
 *
 * The bots' production user IDs are built in, so the block holds before any
 * account is ready. Each account also resolves the bot usernames once it is
 * ready and adds whatever IDs it gets (the test DC uses different IDs). The set
 * only ever grows, so a failed or wrong lookup can never unblock a chat.
 */
object SensitiveChats {
    /** Telegram's service notifications chat (private chat with user 777000). */
    const val TELEGRAM_SERVICE_CHAT_ID = 777000L
    const val VERIFICATION_CODES_CHAT_ID = 489000L
    const val BOT_FATHER_USER_ID = 93372553L
    const val SPAM_BOT_USER_ID = 178220800L

    /** Usernames that every ready account resolves and adds to the set. */
    val BOT_USERNAMES: List<String> = listOf("BotFather", "SpamBot")

    private val builtIn = setOf(TELEGRAM_SERVICE_CHAT_ID, VERIFICATION_CODES_CHAT_ID, BOT_FATHER_USER_ID, SPAM_BOT_USER_ID)
    private val resolved: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    fun isSensitive(chatId: Long): Boolean = chatId in builtIn || chatId in resolved

    /**
     * Adds a chat ID resolved at runtime from one of [BOT_USERNAMES]. Only
     * private chats (positive IDs) are accepted: those are the bots' own chats.
     */
    fun registerResolved(chatId: Long): Boolean = chatId > 0 && resolved.add(chatId)

    /** Every sensitive chat ID known right now. */
    fun knownIds(): Set<Long> = builtIn + resolved

    internal fun clearResolvedForTests() = resolved.clear()
}

/** Direct access to a sensitive chat without an explicit operator opt-in. */
class SensitiveChatBlockedException(chatId: Long?) : dev.telegrammcp.server.exception.ChatNotAllowedException(
    (chatId?.let { "Chat $it" } ?: "The resolved chat") +
        " is a sensitive Telegram chat (login or verification codes, @BotFather or @SpamBot) " +
        "and is blocked by default. The operator can allow it with TELEGRAM_ALLOW_SENSITIVE_CHATS=true, " +
        "a permission grant with allow_sensitive_chats, or by listing the chat ID explicitly.",
)
