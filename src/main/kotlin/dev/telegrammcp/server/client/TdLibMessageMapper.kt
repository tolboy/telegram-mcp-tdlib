package dev.telegrammcp.server.client

import dev.telegrammcp.server.model.MessageForwardOrigin
import dev.telegrammcp.server.model.MessageReplyQuote
import dev.telegrammcp.server.model.TelegramMessage
import dev.telegrammcp.server.model.TelegramPoll
import dev.telegrammcp.server.model.TelegramPollOption
import it.tdlight.jni.TdApi
import java.time.Instant

/** Pure mapping of data already present on a message; never fetches forward or reply origins. */
internal object TdLibMessageMapper {
    fun map(message: TdApi.Message, chatTitle: String?, senderName: String): TelegramMessage {
        val reply = message.replyTo as? TdApi.MessageReplyToMessage
        val forward = message.forwardInfo?.let(::forwardOrigin)
        return TelegramMessage(
            messageId = message.id,
            chatId = message.chatId,
            chatTitle = chatTitle,
            senderName = senderName,
            senderId = when (val sender = message.senderId) {
                is TdApi.MessageSenderUser -> sender.userId
                is TdApi.MessageSenderChat -> sender.chatId
                else -> null
            },
            text = text(message.content),
            date = Instant.ofEpochSecond(message.date.toLong()),
            replyToMessageId = reply?.messageId,
            messageThreadId = when (val topic = message.topicId) {
                is TdApi.MessageTopicThread -> topic.messageThreadId.takeIf { it != 0L }
                is TdApi.MessageTopicForum -> topic.forumTopicId.toLong().takeIf { it != 0L }
                is TdApi.MessageTopicDirectMessages -> topic.directMessagesChatTopicId.takeIf { it != 0L }
                is TdApi.MessageTopicSavedMessages -> topic.savedMessagesTopicId.takeIf { it != 0L }
                else -> null
            },
            mediaType = mediaType(message.content),
            forwardFrom = forward?.let {
                it.senderName ?: it.userId?.let { id -> "User($id)" } ?: it.chatId?.let { id -> "Chat($id)" }
            },
            poll = (message.content as? TdApi.MessagePoll)?.poll?.let { poll ->
                TelegramPoll(
                    question = poll.question?.text.orEmpty(),
                    totalVoterCount = poll.totalVoterCount,
                    isAnonymous = poll.isAnonymous,
                    isClosed = poll.isClosed,
                    options = poll.options?.map { option ->
                        TelegramPollOption(option.text?.text.orEmpty(), option.voterCount, option.votePercentage, option.isChosen)
                    }.orEmpty(),
                )
            },
            isOutgoing = message.isOutgoing,
            editDate = message.editDate.takeIf { it > 0 }?.let { Instant.ofEpochSecond(it.toLong()) },
            mediaAlbumId = message.mediaAlbumId.takeIf { it != 0L }?.toString(),
            forwardOrigin = forward,
            replyInChatId = reply?.chatId?.takeIf { it != 0L },
            replyQuote = reply?.quote?.let { MessageReplyQuote(it.text?.text.orEmpty(), it.position, it.isManual) },
            contentType = message.content?.javaClass?.simpleName?.removePrefix("Message")
                ?.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")?.lowercase(),
        )
    }

    private fun forwardOrigin(info: TdApi.MessageForwardInfo): MessageForwardOrigin? {
        val date = Instant.ofEpochSecond(info.date.toLong())
        return when (val origin = info.origin) {
            is TdApi.MessageOriginUser -> MessageForwardOrigin("user", date, userId = origin.senderUserId)
            is TdApi.MessageOriginChat -> MessageForwardOrigin("chat", date, chatId = origin.senderChatId,
                authorSignature = origin.authorSignature?.takeIf(String::isNotEmpty))
            is TdApi.MessageOriginChannel -> MessageForwardOrigin("channel", date, chatId = origin.chatId,
                messageId = origin.messageId, authorSignature = origin.authorSignature?.takeIf(String::isNotEmpty))
            is TdApi.MessageOriginHiddenUser -> MessageForwardOrigin("hidden_user", date, senderName = origin.senderName)
            else -> null
        }
    }

    private fun text(content: TdApi.MessageContent?): String? = when (content) {
        is TdApi.MessageText -> content.text?.text
        is TdApi.MessagePhoto -> content.caption?.text ?: "[Photo]"
        is TdApi.MessageVideo -> content.caption?.text ?: "[Video]"
        is TdApi.MessageDocument -> content.caption?.text ?: "[Document: ${content.document?.fileName}]"
        is TdApi.MessageAudio -> content.caption?.text ?: "[Audio]"
        is TdApi.MessageVoiceNote -> content.caption?.text ?: "[Voice]"
        is TdApi.MessageSticker -> "[Sticker: ${content.sticker?.emoji}]"
        is TdApi.MessageAnimation -> content.caption?.text ?: "[GIF]"
        is TdApi.MessageVideoNote -> "[Video note]"
        is TdApi.MessagePoll -> "[Poll: ${content.poll?.question?.text}]"
        is TdApi.MessageLocation -> "[Location: ${content.location?.latitude}, ${content.location?.longitude}]"
        is TdApi.MessageContact -> "[Contact: ${content.contact?.firstName} ${content.contact?.lastName}]"
        is TdApi.MessageAnimatedEmoji -> content.emoji
        is TdApi.MessageVenue -> content.venue?.let { "[Venue: ${it.title}, ${it.address}]" } ?: "[Venue]"
        is TdApi.MessageDice -> "[Dice: ${content.emoji}, ${content.value.takeIf { it > 0 } ?: "pending"}]"
        is TdApi.MessageStory -> "[Story: chat ${content.storyPosterChatId}, id ${content.storyId}]"
        is TdApi.MessagePaidMedia -> content.caption?.text ?: "[Paid media: ${content.media?.size ?: 0} items]"
        is TdApi.MessageChecklist -> buildString {
            append("[Checklist: ${content.list?.title?.text.orEmpty()}]")
            content.list?.tasks?.forEach { task ->
                append('\n').append(if (task.completedBy != null) "[x] " else "[ ] ").append(task.text?.text.orEmpty())
            }
        }
        is TdApi.MessageChatChangeTitle -> "[Chat title changed: ${content.title}]"
        is TdApi.MessageChatAddMembers -> "[Members added: ${content.memberUserIds?.joinToString().orEmpty()}]"
        is TdApi.MessageChatDeleteMember -> "[Member removed: ${content.userId}]"
        is TdApi.MessageChatJoinByLink -> "[Member joined by invite link]"
        is TdApi.MessagePinMessage -> "[Message pinned: ${content.messageId}]"
        is TdApi.MessageCustomServiceAction -> content.text
        else -> "[${content?.javaClass?.simpleName ?: "Unknown"}]"
    }

    private fun mediaType(content: TdApi.MessageContent?): String? = when (content) {
        is TdApi.MessageText, is TdApi.MessageAnimatedEmoji -> null
        is TdApi.MessagePhoto -> "photo"
        is TdApi.MessageVideo -> "video"
        is TdApi.MessageDocument -> "document"
        is TdApi.MessageAudio -> "audio"
        is TdApi.MessageVoiceNote -> "voice"
        is TdApi.MessageSticker -> "sticker"
        is TdApi.MessageAnimation -> "animation"
        is TdApi.MessageVideoNote -> "video_note"
        is TdApi.MessagePoll -> "poll"
        is TdApi.MessageLocation -> "location"
        is TdApi.MessageContact -> "contact"
        is TdApi.MessageVenue -> "venue"
        is TdApi.MessageDice -> "dice"
        is TdApi.MessageStory -> "story"
        is TdApi.MessagePaidMedia -> "paid_media"
        is TdApi.MessageChecklist -> "checklist"
        is TdApi.MessageChatChangeTitle, is TdApi.MessageChatAddMembers, is TdApi.MessageChatDeleteMember,
        is TdApi.MessageChatJoinByLink, is TdApi.MessagePinMessage, is TdApi.MessageCustomServiceAction -> "service"
        else -> "other"
    }
}
