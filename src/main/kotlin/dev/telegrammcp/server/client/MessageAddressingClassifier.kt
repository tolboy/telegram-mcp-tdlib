package dev.telegrammcp.server.client

import dev.telegrammcp.server.model.MessageAddressing
import it.tdlight.jni.TdApi

/** Pure classification of a fetched message against the signed-in account; never fetches. */
internal object MessageAddressingClassifier {
    /** The replied-to message ID when the reply stays in the message's own chat. */
    fun sameChatReplyTarget(message: TdApi.Message): Long? {
        val reply = message.replyTo as? TdApi.MessageReplyToMessage ?: return null
        return reply.messageId.takeIf { it > 0 && (reply.chatId == 0L || reply.chatId == message.chatId) }
    }

    fun classify(message: TdApi.Message, chatType: TdApi.ChatType?, me: TdApi.User, myMessageIds: Set<Long>): MessageAddressing {
        val senderId = when (val sender = message.senderId) {
            is TdApi.MessageSenderUser -> sender.userId
            is TdApi.MessageSenderChat -> sender.chatId
            else -> null
        }
        val repliesToMe = sameChatReplyTarget(message)?.let { it in myMessageIds } ?: false
        val mentionsMe = mentions(message.content, me) || (message.containsUnreadMention && !repliesToMe)
        return MessageAddressing(
            chatId = message.chatId,
            messageId = message.id,
            senderId = senderId,
            isOutgoing = message.isOutgoing,
            // Saved Messages is a private chat with yourself, not a conversation partner.
            privateChat = (chatType is TdApi.ChatTypePrivate && chatType.userId != me.id) || chatType is TdApi.ChatTypeSecret,
            mentionsMe = mentionsMe,
            repliesToMe = repliesToMe,
        )
    }

    private fun mentions(content: TdApi.MessageContent?, me: TdApi.User): Boolean {
        val text = formattedText(content) ?: return false
        val usernames = me.usernames?.let { names ->
            (names.activeUsernames.orEmpty().asSequence() + sequenceOf(names.editableUsername))
                .filterNotNull().filter { it.isNotBlank() }.map { it.lowercase() }.toSet()
        }.orEmpty()
        return text.entities.orEmpty().any { entity ->
            when (val type = entity.type) {
                is TdApi.TextEntityTypeMentionName -> type.userId == me.id
                is TdApi.TextEntityTypeMention -> {
                    val end = entity.offset + entity.length
                    entity.offset >= 0 && end <= text.text.length &&
                        text.text.substring(entity.offset, end).removePrefix("@").lowercase() in usernames
                }
                else -> false
            }
        }
    }

    private fun formattedText(content: TdApi.MessageContent?): TdApi.FormattedText? = when (content) {
        is TdApi.MessageText -> content.text
        is TdApi.MessagePhoto -> content.caption
        is TdApi.MessageVideo -> content.caption
        is TdApi.MessageDocument -> content.caption
        is TdApi.MessageAudio -> content.caption
        is TdApi.MessageVoiceNote -> content.caption
        is TdApi.MessageAnimation -> content.caption
        is TdApi.MessagePaidMedia -> content.caption
        else -> null
    }
}
