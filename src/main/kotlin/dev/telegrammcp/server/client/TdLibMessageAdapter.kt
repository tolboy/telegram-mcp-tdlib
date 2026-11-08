package dev.telegrammcp.server.client

import dev.telegrammcp.server.exception.ChatNotAllowedException
import dev.telegrammcp.server.config.TdLibProperties
import dev.telegrammcp.server.exception.ForwardPartiallyDeliveredException
import dev.telegrammcp.server.exception.SendOutcomeUnknownException
import dev.telegrammcp.server.exception.TelegramTimeoutException
import dev.telegrammcp.server.exception.TelegramUnavailableException
import dev.telegrammcp.server.model.*
import dev.telegrammcp.server.util.StructuredLogger
import dev.telegrammcp.server.util.TextFormatter
import io.github.resilience4j.bulkhead.Bulkhead
import io.github.resilience4j.bulkhead.BulkheadConfig
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.ratelimiter.RateLimiter
import io.micrometer.core.instrument.MeterRegistry
import it.tdlight.client.SimpleTelegramClient
import dev.telegrammcp.server.exception.TelegramApiException
import it.tdlight.jni.TdApi
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Message sends, edits, forwards, and other content the user posts. */
internal class TdLibMessageAdapter(
    private val support: TdLibClientSupport,
    private val reads: TdLibReadAdapter,
) {

    fun sendMessage(
        chatId: Long,
        text: String,
        parseMode: ParseMode,
        replyMarkup: ReplyMarkupSpec?,
        messageThreadId: Long?,
    ): TelegramMessage = with(support) {
                log.info(
                    "Sending message to chat {} (thread={}, parseMode={}, replyMarkup={})",
                    chatId, messageThreadId, parseMode, replyMarkup?.type,
                )

                val formattedText = formatText(text, parseMode)
                val content = TdApi.InputMessageText().apply {
                    this.text = formattedText
                    this.clearDraft = false
                }
                val request = TdApi.SendMessage().apply {
                    this.chatId = chatId
                    this.topicId = messageThreadId?.let(::forumThreadTopic)
                    this.inputMessageContent = content
                    this.replyMarkup = replyMarkup?.toTdLib()
                }

                val message = sendAndAwaitFinal("sendMessage", request)
                return message.toTelegramMessage(getChatTitleSafe(chatId))
            
    }


    fun replyToMessage(
        chatId: Long,
        replyToMessageId: Long,
        text: String,
        parseMode: ParseMode,
    ): TelegramMessage = with(support) {
                log.info("Replying to message {} in chat {} (parseMode={})", replyToMessageId, chatId, parseMode)

                val formattedText = formatText(text, parseMode)
                val content = TdApi.InputMessageText().apply {
                    this.text = formattedText
                    this.clearDraft = false
                }
                val replyTo = TdApi.InputMessageReplyToMessage().apply {
                    this.messageId = replyToMessageId
                }
                val request = TdApi.SendMessage().apply {
                    this.chatId = chatId
                    this.replyTo = replyTo
                    this.inputMessageContent = content
                }

                val message = sendAndAwaitFinal("sendMessage", request)
                val sent = message.toTelegramMessage(getChatTitleSafe(chatId))
                if (sent.replyToMessageId != replyToMessageId) {
                    runCatching {
                        send<TdApi.Ok>(TdApi.DeleteMessages(chatId, longArrayOf(sent.messageId), true))
                    }.onFailure {
                        log.warn("Failed to roll back unlinked reply message {} in chat {}: {}", sent.messageId, chatId, it.message)
                    }
                    throw IllegalStateException("Telegram did not attach reply to message $replyToMessageId in chat $chatId")
                }
                return sent
            
    }


    fun editMessage(chatId: Long, messageId: Long, text: String, parseMode: ParseMode): TelegramMessage = with(support) {
                log.info("Editing message {} in chat {} (parseMode={})", messageId, chatId, parseMode)

                val formattedText = formatText(text, parseMode)
                val content = TdApi.InputMessageText().apply {
                    this.text = formattedText
                    this.clearDraft = false
                }
                val request = TdApi.EditMessageText().apply {
                    this.chatId = chatId
                    this.messageId = messageId
                    this.inputMessageContent = content
                }

                val message = withResilience("editMessageText") { send<TdApi.Message>(request) }
                return message.toTelegramMessage(getChatTitleSafe(chatId))
            
    }


    fun sendFile(chatId: Long, filePath: String, caption: String?): TelegramMessage = with(support) {
                log.info("Sending file '{}' to chat {}", filePath, chatId)

                val inputFile = TdApi.InputFileLocal(filePath)
                val captionText = caption?.let { TdApi.FormattedText(it, emptyArray()) }

                val content = TdApi.InputMessageDocument().apply {
                    this.document = TdApi.InputDocument(inputFile, null, false)
                    this.caption = captionText
                }

                val request = TdApi.SendMessage().apply {
                    this.chatId = chatId
                    this.inputMessageContent = content
                }

                val message = sendAndAwaitFinal("sendMessage", request)
                return message.toTelegramMessage(getChatTitleSafe(chatId))
            
    }


    // ── Extended message operations ────────────────────────────────────────
    fun deleteMessages(chatId: Long, messageIds: List<Long>, revoke: Boolean): Boolean = with(support) {
                log.info("Deleting {} messages from chat {} (revoke={})", messageIds.size, chatId, revoke)

                withResilience("deleteMessages") {
                    send<TdApi.Ok>(TdApi.DeleteMessages(chatId, messageIds.toLongArray(), revoke))
                }
                return true
            
    }


    fun forwardMessages(fromChatId: Long, toChatId: Long, messageIds: List<Long>): List<TelegramMessage> = with(support) {
                log.info("Forwarding {} messages from chat {} to chat {}", messageIds.size, fromChatId, toChatId)

                val request = TdApi.ForwardMessages().apply {
                    this.chatId = toChatId
                    this.fromChatId = fromChatId
                    this.messageIds = messageIds.toLongArray()
                }

                // The bulkhead permit covers the final-state waits too (see holdingBulkhead).
                val delivered = holdingBulkhead("forwardMessages") { forwardAndAwait(request, toChatId, messageIds) }
                val chatTitle = getChatTitleSafe(toChatId)
                return delivered.map { it.toTelegramMessage(chatTitle) }
            
    }


    fun pinMessage(chatId: Long, messageId: Long, disableNotification: Boolean): Boolean = with(support) {
                log.info("Pinning message {} in chat {} (silent={})", messageId, chatId, disableNotification)

                withResilience("pinChatMessage") {
                    send<TdApi.Ok>(TdApi.PinChatMessage(chatId, messageId, disableNotification, false))
                }
                return true
            
    }


    fun unpinMessage(chatId: Long, messageId: Long): Boolean = with(support) {
                log.info("Unpinning message {} in chat {}", messageId, chatId)

                withResilience("unpinChatMessage") {
                    send<TdApi.Ok>(TdApi.UnpinChatMessage(chatId, messageId))
                }
                return true
            
    }


    fun scheduleMessage(
        chatId: Long,
        text: String,
        sendAtEpochSeconds: Int,
        repeatPeriodSeconds: Int,
        disableNotification: Boolean,
        parseMode: ParseMode,
    ): ScheduledMessage = with(support) {
                log.info("Scheduling message for chat {} at {}", chatId, sendAtEpochSeconds)
                val content = TdApi.InputMessageText().apply {
                    this.text = formatText(text, parseMode)
                    this.clearDraft = false
                }
                val request = TdApi.SendMessage().apply {
                    this.chatId = chatId
                    this.options = TdApi.MessageSendOptions().apply {
                        this.disableNotification = disableNotification
                        this.schedulingState = TdApi.MessageSchedulingStateSendAtDate(sendAtEpochSeconds, repeatPeriodSeconds)
                    }
                    this.inputMessageContent = content
                }
                val message = sendAndAwaitFinal("sendMessage", request)
                return message.toScheduledMessage(getChatTitleSafe(chatId))
            
    }


    fun rescheduleMessage(
        chatId: Long,
        messageId: Long,
        sendAtEpochSeconds: Int,
        repeatPeriodSeconds: Int,
    ): ScheduledMessage = with(support) {
                log.info("Rescheduling message {} in chat {} for {}", messageId, chatId, sendAtEpochSeconds)
                val before = reads.getScheduledMessages(chatId)
                val original = before.firstOrNull { it.message.messageId == messageId }
                    ?: throw IllegalArgumentException("Scheduled message $messageId was not found in chat $chatId")
                withResilience("editMessageSchedulingState") {
                    send<TdApi.Ok>(
                        TdApi.EditMessageSchedulingState(
                            chatId,
                            messageId,
                            TdApi.MessageSchedulingStateSendAtDate(sendAtEpochSeconds, repeatPeriodSeconds),
                        ),
                    )
                }
                val previousIds = before.mapTo(mutableSetOf()) { it.message.messageId }
                return reads.getScheduledMessages(chatId)
                    .asSequence()
                    .filter {
                        it.sendAt?.epochSecond == sendAtEpochSeconds.toLong() &&
                            it.repeatPeriodSeconds == repeatPeriodSeconds &&
                            it.message.text == original.message.text &&
                            it.message.mediaType == original.message.mediaType &&
                            it.message.replyToMessageId == original.message.replyToMessageId &&
                            it.message.messageThreadId == original.message.messageThreadId
                    }
                    .sortedBy { candidate ->
                        when {
                            candidate.message.messageId !in previousIds -> 0
                            candidate.message.messageId == messageId -> 1
                            else -> 2
                        }
                    }
                    .firstOrNull()
                    ?: throw IllegalStateException(
                        "Telegram rescheduled message $messageId in chat $chatId, " +
                            "but the replacement could not be identified",
                    )
            
    }


    fun cancelScheduledMessage(chatId: Long, messageId: Long): Boolean = with(support) {
                log.info("Cancelling scheduled message {} in chat {}", messageId, chatId)
                withResilience("deleteMessages") {
                    send<TdApi.Ok>(TdApi.DeleteMessages(chatId, longArrayOf(messageId), true))
                }
                return true
            
    }


    // ── Reactions & Polls ──────────────────────────────────────────────────
    fun addReaction(chatId: Long, messageId: Long, emoji: String, isBig: Boolean): Boolean = with(support) {
                log.info("Adding reaction '{}' to message {} in chat {}", emoji, messageId, chatId)

                val request = TdApi.AddMessageReaction().apply {
                    this.chatId = chatId
                    this.messageId = messageId
                    this.reactionType = TdApi.ReactionTypeEmoji(emoji)
                    this.isBig = isBig
                    this.updateRecentReactions = true
                }
                withResilience("addMessageReaction") { send<TdApi.Ok>(request) }
                return true
            
    }


    fun removeReaction(chatId: Long, messageId: Long, emoji: String): Boolean = with(support) {
                log.info("Removing reaction '{}' from message {} in chat {}", emoji, messageId, chatId)

                val request = TdApi.RemoveMessageReaction(chatId, messageId, TdApi.ReactionTypeEmoji(emoji))
                withResilience("removeMessageReaction") { send<TdApi.Ok>(request) }
                return true
            
    }


    fun sendPoll(
        chatId: Long,
        question: String,
        options: List<String>,
        isAnonymous: Boolean,
        allowMultipleAnswers: Boolean,
    ): TelegramMessage = with(support) {
                log.info("Sending poll to chat {} ('{}', {} options)", chatId, question, options.size)

                val pollContent = TdApi.InputMessagePoll().apply {
                    this.question = TdApi.FormattedText(question, emptyArray())
                    this.options = options
                        .map { TdApi.InputPollOption(TdApi.FormattedText(it, emptyArray()), null) }
                        .toTypedArray()
                    this.isAnonymous = isAnonymous
                    this.allowsMultipleAnswers = allowMultipleAnswers
                    this.type = TdApi.InputPollTypeRegular(false)
                    this.openPeriod = 0
                    this.closeDate = 0
                    this.isClosed = false
                }

                val request = TdApi.SendMessage().apply {
                    this.chatId = chatId
                    this.inputMessageContent = pollContent
                }

                val message = sendAndAwaitFinal("sendMessage", request)
                return message.toTelegramMessage(getChatTitleSafe(chatId))
            
    }


    fun setPollAnswer(chatId: Long, messageId: Long, optionIds: List<Int>): Boolean = with(support) {
                log.info("Voting in poll (message {} in chat {}) with {} option(s)", messageId, chatId, optionIds.size)

                withResilience("setPollAnswer") {
                    send<TdApi.Ok>(TdApi.SetPollAnswer(chatId, messageId, optionIds.toIntArray()))
                }
                return true
            
    }


    fun stopPoll(chatId: Long, messageId: Long): Boolean = with(support) {
                log.info("Closing poll (message {} in chat {})", messageId, chatId)

                withResilience("stopPoll") {
                    send<TdApi.Ok>(TdApi.StopPoll(chatId, messageId, null))
                }
                return true
            
    }


    fun pressInlineButton(
        chatId: Long,
        messageId: Long,
        buttonIndex: Int?,
        buttonText: String?,
    ): String = with(support) {
                log.info("Pressing inline button (index={}, text={}) on message {} in chat {}", buttonIndex, buttonText, messageId, chatId)

                val buttons = reads.listInlineButtons(chatId, messageId)
                if (buttons.isEmpty()) throw IllegalStateException("Message has no inline keyboard")

                val target = when {
                    buttonIndex != null -> buttons.getOrNull(buttonIndex)
                        ?: throw IllegalArgumentException("button_index $buttonIndex out of range (0..${buttons.lastIndex})")
                    !buttonText.isNullOrBlank() -> buttons.firstOrNull { it.text.equals(buttonText, ignoreCase = true) }
                        ?: throw IllegalArgumentException("No button with text '$buttonText'")
                    else -> throw IllegalArgumentException("Either button_index or button_text is required")
                }

                return when (target.type) {
                    "url", "login_url", "web_app" -> target.url ?: ""
                    "callback" -> {
                        val payload = TdApi.CallbackQueryPayloadData(target.callbackData?.toByteArray() ?: ByteArray(0))
                        val request = TdApi.GetCallbackQueryAnswer().apply {
                            this.chatId = chatId
                            this.messageId = messageId
                            this.payload = payload
                        }
                        val answer = withResilience("getCallbackQueryAnswer") {
                            send<TdApi.CallbackQueryAnswer>(request)
                        }
                        answer.text ?: ""
                    }
                    else -> throw IllegalStateException("Button type '${target.type}' is not supported for automated press")
                }
            
    }


    // ── Voice, stickers & chat photo ───────────────────────────────────────
    fun sendVoice(chatId: Long, filePath: String, duration: Int, caption: String?): TelegramMessage = with(support) {
                log.info("Sending voice '{}' to chat {} (duration={}s)", filePath, chatId, duration)

                val content = TdLibRequestBuilders.voiceNoteContent(filePath, duration, caption)
                val request = TdApi.SendMessage().apply {
                    this.chatId = chatId
                    this.inputMessageContent = content
                }
                val message = sendAndAwaitFinal("sendMessage", request)
                return message.toTelegramMessage(getChatTitleSafe(chatId))
            
    }


    fun sendSticker(chatId: Long, filePath: String, emoji: String?): TelegramMessage = with(support) {
                log.info("Sending sticker '{}' to chat {}", filePath, chatId)

                val content = TdLibRequestBuilders.stickerContent(filePath, emoji)
                val request = TdApi.SendMessage().apply {
                    this.chatId = chatId
                    this.inputMessageContent = content
                }
                val message = sendAndAwaitFinal("sendMessage", request)
                return message.toTelegramMessage(getChatTitleSafe(chatId))
            
    }


    // ── Albums, rich sends and moderation ──────────────────────────────────
    fun sendAlbum(chatId: Long, items: List<AlbumItem>): List<TelegramMessage> = with(support) {
                log.info("Sending album of {} items to chat {}", items.size, chatId)

                val request = TdLibRequestBuilders.albumRequest(chatId, items)
                val delivered = holdingBulkhead("sendMessageAlbum") { albumAndAwait(request, chatId) }
                val chatTitle = getChatTitleSafe(chatId)
                return delivered.map { it.toTelegramMessage(chatTitle) }
            
    }


    fun sendChatAction(chatId: Long, action: ChatActionType): Boolean = with(support) {
                log.info("Sending chat action {} to chat {}", action, chatId)
                withResilience("sendChatAction") {
                    send<TdApi.Ok>(TdApi.SendChatAction(chatId, null, "", TdLibRequestBuilders.chatAction(action)))
                }
                return true
            
    }


    fun sendLocation(chatId: Long, latitude: Double, longitude: Double, horizontalAccuracy: Double): TelegramMessage = with(support) {
                log.info("Sending location to chat {}", chatId)
                return sendContent(chatId, TdLibRequestBuilders.locationContent(latitude, longitude, horizontalAccuracy))
            
    }


    fun sendVenue(chatId: Long, venue: VenueSpec): TelegramMessage = with(support) {
                log.info("Sending venue to chat {}", chatId)
                return sendContent(chatId, TdLibRequestBuilders.venueContent(venue))
            
    }


    fun sendContact(chatId: Long, contact: ContactSpec): TelegramMessage = with(support) {
                log.info("Sending contact card to chat {}", chatId)
                return sendContent(chatId, TdLibRequestBuilders.contactContent(contact))
            
    }
}
