package dev.telegrammcp.server.resource

/**
 * Canonical `telegram://` resource URI.
 *
 * Only the canonical spelling parses: lowercase account label, decimal chat and
 * message IDs without signs or leading zeros other than a chat's leading `-`.
 * Subscriptions and change notifications compare URIs as strings, so a second
 * spelling of the same resource would never be notified.
 */
sealed class TelegramResourceUri {
    abstract val account: String

    /** The read tool whose permissions and visibility govern this resource. */
    abstract val backingTool: String

    /** Chat whose scope must admit the reader, or null for an account-level resource. */
    open val chatId: Long? get() = null

    data class Chats(override val account: String) : TelegramResourceUri() {
        override val backingTool get() = "list_chats"
        override fun toString() = "$SCHEME$account/chats"
    }

    data class Chat(override val account: String, override val chatId: Long) : TelegramResourceUri() {
        override val backingTool get() = "conversation_bundle"
        override fun toString() = "$SCHEME$account/chat/$chatId"
    }

    data class Message(override val account: String, override val chatId: Long, val messageId: Long) : TelegramResourceUri() {
        override val backingTool get() = "get_messages"
        override fun toString() = "$SCHEME$account/chat/$chatId/message/$messageId"
    }

    companion object {
        const val SCHEME = "telegram://"
        const val CHATS_TEMPLATE = "telegram://{account}/chats"
        const val CHAT_TEMPLATE = "telegram://{account}/chat/{chatId}"
        const val MESSAGE_TEMPLATE = "telegram://{account}/chat/{chatId}/message/{messageId}"
        const val MAX_LENGTH = 256

        private val PATTERN = Regex(
            "^telegram://([a-z0-9][a-z0-9_-]{0,63})/(?:(chats)|chat/(-?[1-9][0-9]{0,18})(?:/message/([1-9][0-9]{0,18}))?)$",
        )

        /** Returns null for anything that is not a canonical server resource URI. */
        fun parse(raw: String?): TelegramResourceUri? {
            if (raw == null || raw.length > MAX_LENGTH) return null
            val match = PATTERN.matchEntire(raw) ?: return null
            val (account, chats, chat, message) = match.destructured
            if (chats.isNotEmpty()) return Chats(account)
            val chatId = chat.toLongOrNull() ?: return null
            if (message.isEmpty()) return Chat(account, chatId)
            val messageId = message.toLongOrNull() ?: return null
            return Message(account, chatId, messageId)
        }
    }
}
