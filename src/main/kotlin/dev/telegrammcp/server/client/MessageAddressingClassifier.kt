package dev.telegrammcp.server.client

import dev.telegrammcp.server.model.MessageAddressing
import it.tdlight.jni.TdApi

/** Pure classification of a fetched message against the signed-in account; never fetches. */
internal object MessageAddressingClassifier {
    /** The replied-to message ID when the reply stays in the message's own chat. */
    fun sameChatReplyTarget(message: TdApi.Message): Long? {
        val reply = message.replyTo as? TdApi.MessageReplyToMessage ?: return null
        return reply.messageId.takeIf { it > 0 && reply.chatId == message.chatId }
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
        val usernames = me.usernames?.let { names ->
            (names.activeUsernames.orEmpty().asSequence() + sequenceOf(names.editableUsername))
                .filterNotNull().filter { it.isNotBlank() }.map { it.lowercase() }.toSet()
        }.orEmpty()
        return formattedTexts(content).filterNotNull().any { text ->
            text.entities.orEmpty().any { entity ->
                when (val type = entity.type) {
                    is TdApi.TextEntityTypeMentionName -> type.userId == me.id
                    is TdApi.TextEntityTypeMention -> {
                        val value = text.text.orEmpty()
                        val offset = entity.offset
                        val length = entity.length
                        offset >= 0 && length > 0 && length <= value.length - offset &&
                            value.substring(offset, offset + length).removePrefix("@").lowercase() in usernames
                    }
                    else -> false
                }
            }
        }
    }

    private fun formattedTexts(content: TdApi.MessageContent?): Sequence<TdApi.FormattedText?> = when (content) {
        is TdApi.MessageText -> sequenceOf(content.text)
        is TdApi.MessagePhoto -> sequenceOf(content.caption)
        is TdApi.MessageVideo -> sequenceOf(content.caption)
        is TdApi.MessageDocument -> sequenceOf(content.caption)
        is TdApi.MessageAudio -> sequenceOf(content.caption)
        is TdApi.MessageVoiceNote -> sequenceOf(content.caption)
        is TdApi.MessageAnimation -> sequenceOf(content.caption)
        is TdApi.MessagePaidMedia -> sequenceOf(content.caption)
        is TdApi.MessageInvoice -> sequenceOf(content.paidMediaCaption)
        is TdApi.MessageGame -> sequenceOf(content.game?.text)
        is TdApi.MessageChecklist -> sequenceOf(content.list?.title) +
            content.list?.tasks.orEmpty().asSequence().map { it.text }
        is TdApi.MessagePoll -> sequenceOf(content.poll?.question, content.description,
            (content.poll?.type as? TdApi.PollTypeQuiz)?.explanation) +
            content.poll?.options.orEmpty().asSequence().map { it.text }
        else -> emptySequence()
    }
}
