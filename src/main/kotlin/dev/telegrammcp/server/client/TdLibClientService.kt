package dev.telegrammcp.server.client

import dev.telegrammcp.server.exception.TelegramUnavailableException
import dev.telegrammcp.server.model.*
import dev.telegrammcp.server.util.StructuredLogger
import dev.telegrammcp.server.util.TextFormatter
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.ratelimiter.RateLimiter
import io.github.resilience4j.ratelimiter.RequestNotPermitted
import io.micrometer.core.instrument.MeterRegistry
import it.tdlight.client.SimpleTelegramClient
import it.tdlight.jni.TdApi
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * TDLib-backed implementation of [TelegramClientService].
 *
 * All outbound calls are guarded by a [RateLimiter] (30 req/s default) and a [CircuitBreaker]
 * that opens after 50% failure rate over a 10-call window. Bridges TDLib's async callback API
 * to synchronous calls required by the MCP SDK.
 */
class TdLibClientService(
    private val client: SimpleTelegramClient,
    private val rateLimiter: RateLimiter,
    private val circuitBreaker: CircuitBreaker,
    private val meterRegistry: MeterRegistry,
    private val chatFolderState: ChatFolderState = ChatFolderState(),
    private val messageSendTracker: MessageSendTracker? = null,
) : TelegramClientService {

    private val log = StructuredLogger.forClass<TdLibClientService>()

    companion object {
        private const val TIMEOUT_SECONDS = 30L
        private const val MESSAGE_SEND_TIMEOUT_SECONDS = 30L
        private const val DEFAULT_FORUM_TOPIC_ICON_COLOR = 0x6FB9F0
        private val ALLOWED_FORUM_TOPIC_ICON_COLORS = setOf(
            0x6FB9F0,
            0xFFD67E,
            0xCB86DB,
            0x8EEE98,
            0xFF93B2,
            0xFB6F5F,
        )
        private const val CREATE_CHAT_REFRESH_ATTEMPTS = 6
        private const val CREATE_CHAT_REFRESH_DELAY_MS = 250L
        private const val SPEECH_RECOGNITION_TIMEOUT_MS = 30_000L
        private const val SPEECH_RECOGNITION_POLL_INTERVAL_MS = 1_000L
    }

    // ── Messages ────────────────────────────────────────────────────────────

    override fun getHistory(chatId: Long, fromMessageId: Long, offset: Int, limit: Int): List<TelegramMessage> {
        log.info("Getting history for chat {} (from={}, offset={}, limit={})", chatId, fromMessageId, offset, limit)

        val messages = withResilience("getChatHistory") {
            send<TdApi.Messages>(TdApi.GetChatHistory(chatId, fromMessageId, offset, limit, false))
        }

        val chatTitle = getChatTitleSafe(chatId)
        return messages.messages?.map { it.toTelegramMessage(chatTitle) } ?: emptyList()
    }

    override fun searchMessages(chatId: Long, query: String, offset: Long, limit: Int): List<TelegramMessage> {
        log.info("Searching '{}' in chat {} (offset={}, limit={})", query, chatId, offset, limit)

        // Use empty constructor + property assignment for API compatibility
        val request = TdApi.SearchChatMessages().apply {
            this.chatId = chatId
            this.query = query
            this.fromMessageId = offset
            this.limit = limit
        }

        val result = withResilience("searchChatMessages") {
            send<TdApi.FoundChatMessages>(request)
        }

        val chatTitle = getChatTitleSafe(chatId)
        return result.messages?.map { it.toTelegramMessage(chatTitle) } ?: emptyList()
    }

    override fun sendMessage(
        chatId: Long,
        text: String,
        parseMode: ParseMode,
        replyMarkup: ReplyMarkupSpec?,
        messageThreadId: Long?,
    ): TelegramMessage {
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

        val message = awaitFinalMessage(withResilience("sendMessage") { send<TdApi.Message>(request) })
        return message.toTelegramMessage(getChatTitleSafe(chatId))
    }

    private fun ReplyMarkupSpec.toTdLib(): TdApi.ReplyMarkup = when (type) {
        ReplyMarkupSpec.Kind.SHOW_KEYBOARD -> TdApi.ReplyMarkupShowKeyboard().apply {
            rows = this@toTdLib.rows.map { row ->
                row.map { buttonText ->
                    TdApi.KeyboardButton(
                        buttonText,
                        0L,
                        TdApi.ButtonStyleDefault(),
                        TdApi.KeyboardButtonTypeText(),
                    )
                }.toTypedArray()
            }.toTypedArray()
            isPersistent = false
            resizeKeyboard = resize
            oneTime = this@toTdLib.oneTime
            isPersonal = true
            inputFieldPlaceholder = placeholder.orEmpty()
        }
        ReplyMarkupSpec.Kind.REMOVE -> TdApi.ReplyMarkupRemoveKeyboard().apply {
            isPersonal = true
        }
    }

    override fun replyToMessage(
        chatId: Long,
        replyToMessageId: Long,
        text: String,
        parseMode: ParseMode,
    ): TelegramMessage {
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

        val message = awaitFinalMessage(withResilience("sendMessage") { send<TdApi.Message>(request) })
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

    override fun editMessage(chatId: Long, messageId: Long, text: String, parseMode: ParseMode): TelegramMessage {
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

    // ── Chats ───────────────────────────────────────────────────────────────

    override fun getChats(limit: Int): List<ChatInfo> {
        log.info("Loading chats (limit={})", limit)

        // First load chats into TDLib's internal cache
        withResilience("loadChats") {
            try {
                send<TdApi.Ok>(TdApi.LoadChats(TdApi.ChatListMain(), limit))
            } catch (_: Exception) {
                // LoadChats may return error when no more chats — this is expected
                TdApi.Ok()
            }
        }

        val chatIds = withResilience("getChats") {
            send<TdApi.Chats>(TdApi.GetChats(TdApi.ChatListMain(), limit))
        }

        return chatIds.chatIds.map { id ->
            withResilience("getChat") { send<TdApi.Chat>(TdApi.GetChat(id)) }.toChatInfo()
        }
    }

    override fun getChat(chatId: Long): ChatInfo {
        log.info("Getting chat details for {}", chatId)

        val chat = withResilience("getChat") { send<TdApi.Chat>(TdApi.GetChat(chatId)) }

        // Try to get full info for member count and description
        val fullInfo = try {
            when (chat.type) {
                is TdApi.ChatTypeSupergroup -> {
                    val sgId = (chat.type as TdApi.ChatTypeSupergroup).supergroupId
                    withResilience("getSupergroupFullInfo") {
                        send<TdApi.SupergroupFullInfo>(TdApi.GetSupergroupFullInfo(sgId))
                    }
                }
                is TdApi.ChatTypeBasicGroup -> {
                    val groupId = (chat.type as TdApi.ChatTypeBasicGroup).basicGroupId
                    withResilience("getBasicGroupFullInfo") {
                        send<TdApi.BasicGroupFullInfo>(TdApi.GetBasicGroupFullInfo(groupId))
                    }
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }

        return chat.toChatInfo(fullInfo)
    }

    override fun listChatFolders(): ChatFolderListing = chatFolderState.snapshot()

    override fun getChatFolder(folderId: Int): ChatFolderDetails {
        require(folderId > 0) { "chat folder ID must be positive" }
        log.info("Getting chat folder {}", folderId)
        val folder = withResilience("getChatFolder") {
            send<TdApi.ChatFolder>(TdApi.GetChatFolder(folderId))
        }
        return ChatFolderDetails(folderId, folder.toChatFolderDefinition())
    }

    override fun createChatFolder(folder: ChatFolderDefinition): ChatFolderInfo {
        log.info("Creating chat folder '{}'", folder.title)
        return withResilience("createChatFolder") {
            send<TdApi.ChatFolderInfo>(TdApi.CreateChatFolder(folder.toTdLib()))
        }.toChatFolderInfo()
    }

    override fun updateChatFolder(folderId: Int, folder: ChatFolderDefinition): ChatFolderInfo {
        require(folderId > 0) { "chat folder ID must be positive" }
        log.info("Updating chat folder {}", folderId)
        return withResilience("editChatFolder") {
            send<TdApi.ChatFolderInfo>(TdApi.EditChatFolder(folderId, folder.toTdLib()))
        }.toChatFolderInfo()
    }

    override fun deleteChatFolder(folderId: Int): Boolean {
        require(folderId > 0) { "chat folder ID must be positive" }
        log.info("Deleting chat folder {} without leaving chats", folderId)
        withResilience("deleteChatFolder") {
            send<TdApi.Ok>(TdApi.DeleteChatFolder(folderId, longArrayOf()))
        }
        return true
    }

    override fun reorderChatFolders(folderIds: List<Int>, mainChatListPosition: Int): Boolean {
        require(folderIds.isNotEmpty()) { "folder ID list must not be empty" }
        require(folderIds.all { it > 0 }) { "chat folder IDs must be positive" }
        log.info("Reordering {} chat folders (main list position {})", folderIds.size, mainChatListPosition)

        withResilience("reorderChatFolders") {
            send<TdApi.Ok>(TdApi.ReorderChatFolders(folderIds.toIntArray(), mainChatListPosition))
        }
        return true
    }

    override fun getChatMembers(chatId: Long, query: String, offset: Int, limit: Int): List<ChatMember> {
        log.info("Getting members of chat {} (query='{}', offset={}, limit={})", chatId, query, offset, limit)

        val members = withResilience("searchChatMembers") {
            send<TdApi.ChatMembers>(TdApi.SearchChatMembers(chatId, query, limit, null))
        }

        return members.members?.map { it.toChatMember() } ?: emptyList()
    }

    // ── Users & Contacts ────────────────────────────────────────────────────

    override fun getMe(): UserInfo {
        log.info("Getting current user profile")

        val me = withResilience("getMe") { send<TdApi.User>(TdApi.GetMe()) }
        return me.toUserInfo()
    }

    override fun getContacts(): List<ContactInfo> {
        log.info("Getting contacts list")

        val contacts = withResilience("getContacts") {
            send<TdApi.Users>(TdApi.GetContacts())
        }

        return contacts.userIds.map { userId ->
            try {
                val user = withResilience("getUser") { send<TdApi.User>(TdApi.GetUser(userId)) }
                ContactInfo(
                    userId = user.id,
                    firstName = user.firstName,
                    lastName = user.lastName?.takeIf { it.isNotBlank() },
                    username = user.usernames?.activeUsernames?.firstOrNull(),
                    phoneNumber = user.phoneNumber?.takeIf { it.isNotBlank() },
                )
            } catch (_: Exception) {
                ContactInfo(userId = userId, firstName = "User($userId)")
            }
        }
    }

    override fun getUser(userId: Long): UserInfo {
        log.info("Getting user info for {}", userId)

        val user = withResilience("getUser") { send<TdApi.User>(TdApi.GetUser(userId)) }
        return user.toUserInfo()
    }

    // ── Media ───────────────────────────────────────────────────────────────

    override fun getMediaInfo(chatId: Long, messageId: Long): MediaInfo {
        log.info("Getting media info for message {} in chat {}", messageId, chatId)

        val messages = withResilience("getMessages") {
            send<TdApi.Messages>(TdApi.GetMessages(chatId, longArrayOf(messageId)))
        }
        val message = messages.messages?.firstOrNull()
            ?: throw IllegalStateException("Message $messageId not found in chat $chatId")

        return message.toMediaInfo(chatId)
    }

    override fun downloadMedia(chatId: Long, messageId: Long): DownloadResult {
        log.info("Downloading media from message {} in chat {}", messageId, chatId)

        val messages = withResilience("getMessages") {
            send<TdApi.Messages>(TdApi.GetMessages(chatId, longArrayOf(messageId)))
        }
        val message = messages.messages?.firstOrNull()
            ?: throw IllegalStateException("Message $messageId not found in chat $chatId")

        val file = extractFileFromMessage(message)
            ?: throw IllegalStateException("Message $messageId contains no downloadable media")

        // Download the file via TDLib
        val downloaded = withResilience("downloadFile") {
            send<TdApi.File>(TdApi.DownloadFile(file.id, 1, 0, 0, true))
        }

        val localPath = downloaded.local?.path
            ?: throw IllegalStateException("Download completed but no local path returned")

        return DownloadResult(
            localPath = localPath,
            fileName = extractFileName(message) ?: "file_${messageId}",
            mimeType = extractMimeType(message),
            fileSize = downloaded.size,
        )
    }

    override fun transcribeVoiceNote(chatId: Long, messageId: Long): VoiceTranscription {
        log.info("Requesting native speech recognition for message {} in chat {}", messageId, chatId)

        val initial = getSpeechRecognitionResult(chatId, messageId)
        initial.toVoiceTranscriptionOrNull(chatId, messageId)?.let { return it }

        withResilience("recognizeSpeech") {
            send<TdApi.Ok>(TdApi.RecognizeSpeech(chatId, messageId))
        }

        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SPEECH_RECOGNITION_TIMEOUT_MS)
        var partialText: String? = initial.partialTextOrNull()
        while (System.nanoTime() < deadline) {
            try {
                Thread.sleep(SPEECH_RECOGNITION_POLL_INTERVAL_MS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw TelegramUnavailableException(interrupted)
            }
            val result = getSpeechRecognitionResult(chatId, messageId)
            result.toVoiceTranscriptionOrNull(chatId, messageId)?.let { return it }
            partialText = result.partialTextOrNull() ?: partialText
        }

        return VoiceTranscription(
            chatId = chatId,
            messageId = messageId,
            status = VoiceTranscriptionStatus.PENDING,
            partialText = partialText,
        )
    }

    private fun getSpeechRecognitionResult(chatId: Long, messageId: Long): TdApi.SpeechRecognitionResult? {
        val messages = withResilience("getMessages") {
            send<TdApi.Messages>(TdApi.GetMessages(chatId, longArrayOf(messageId)))
        }
        val message = messages.messages?.firstOrNull()
            ?: throw IllegalStateException("Message $messageId not found in chat $chatId")
        val voiceNote = (message.content as? TdApi.MessageVoiceNote)?.voiceNote
            ?: throw IllegalArgumentException("Message $messageId in chat $chatId is not a voice note")
        return voiceNote.speechRecognitionResult
    }

    private fun TdApi.SpeechRecognitionResult?.toVoiceTranscriptionOrNull(
        chatId: Long,
        messageId: Long,
    ): VoiceTranscription? = when (this) {
        is TdApi.SpeechRecognitionResultText -> VoiceTranscription(
            chatId = chatId,
            messageId = messageId,
            status = VoiceTranscriptionStatus.COMPLETED,
            text = text,
        )
        is TdApi.SpeechRecognitionResultError -> throw IllegalStateException(
            "Telegram speech recognition failed (${error?.code}): ${error?.message}",
        )
        else -> null
    }

    private fun TdApi.SpeechRecognitionResult?.partialTextOrNull(): String? =
        (this as? TdApi.SpeechRecognitionResultPending)?.partialText?.takeIf { it.isNotBlank() }

    override fun sendFile(chatId: Long, filePath: String, caption: String?): TelegramMessage {
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

        val message = awaitFinalMessage(withResilience("sendMessage") { send<TdApi.Message>(request) })
        return message.toTelegramMessage(getChatTitleSafe(chatId))
    }

    // ── Extended message operations ────────────────────────────────────────

    override fun deleteMessages(chatId: Long, messageIds: List<Long>, revoke: Boolean): Boolean {
        log.info("Deleting {} messages from chat {} (revoke={})", messageIds.size, chatId, revoke)

        withResilience("deleteMessages") {
            send<TdApi.Ok>(TdApi.DeleteMessages(chatId, messageIds.toLongArray(), revoke))
        }
        return true
    }

    override fun forwardMessages(fromChatId: Long, toChatId: Long, messageIds: List<Long>): List<TelegramMessage> {
        log.info("Forwarding {} messages from chat {} to chat {}", messageIds.size, fromChatId, toChatId)

        val request = TdApi.ForwardMessages().apply {
            this.chatId = toChatId
            this.fromChatId = fromChatId
            this.messageIds = messageIds.toLongArray()
        }

        val result = withResilience("forwardMessages") {
            send<TdApi.Messages>(request)
        }
        val chatTitle = getChatTitleSafe(toChatId)
        return result.messages?.map { awaitFinalMessage(it).toTelegramMessage(chatTitle) } ?: emptyList()
    }

    override fun pinMessage(chatId: Long, messageId: Long, disableNotification: Boolean): Boolean {
        log.info("Pinning message {} in chat {} (silent={})", messageId, chatId, disableNotification)

        withResilience("pinChatMessage") {
            send<TdApi.Ok>(TdApi.PinChatMessage(chatId, messageId, disableNotification, false))
        }
        return true
    }

    override fun unpinMessage(chatId: Long, messageId: Long): Boolean {
        log.info("Unpinning message {} in chat {}", messageId, chatId)

        withResilience("unpinChatMessage") {
            send<TdApi.Ok>(TdApi.UnpinChatMessage(chatId, messageId))
        }
        return true
    }

    override fun getPinnedMessages(chatId: Long): List<TelegramMessage> {
        log.info("Getting pinned messages for chat {}", chatId)

        val request = TdApi.SearchChatMessages().apply {
            this.chatId = chatId
            this.query = ""
            this.limit = 100
            this.filter = TdApi.SearchMessagesFilterPinned()
        }

        val result = withResilience("searchChatMessages") {
            send<TdApi.FoundChatMessages>(request)
        }

        val chatTitle = getChatTitleSafe(chatId)
        return result.messages?.map { it.toTelegramMessage(chatTitle) } ?: emptyList()
    }

    override fun viewMessages(chatId: Long, messageIds: List<Long>): Boolean {
        log.info("Marking {} messages as read in chat {}", messageIds.size, chatId)

        val request = TdApi.ViewMessages().apply {
            this.chatId = chatId
            this.messageIds = messageIds.toLongArray()
            this.forceRead = true
        }

        withResilience("viewMessages") { send<TdApi.Ok>(request) }
        return true
    }

    override fun searchGlobal(query: String, limit: Int): List<TelegramMessage> {
        log.info("Global search for '{}' (limit={})", query, limit)

        val request = TdApi.SearchMessages().apply {
            this.query = query
            this.limit = limit
        }

        val result = withResilience("searchMessages") {
            send<TdApi.FoundMessages>(request)
        }

        return result.messages?.map { msg ->
            val chatTitle = getChatTitleSafe(msg.chatId)
            msg.toTelegramMessage(chatTitle)
        } ?: emptyList()
    }

    override fun getScheduledMessages(chatId: Long): List<ScheduledMessage> {
        log.info("Getting scheduled messages for chat {}", chatId)
        val messages = withResilience("getChatScheduledMessages") {
            send<TdApi.Messages>(TdApi.GetChatScheduledMessages(chatId))
        }
        val chatTitle = getChatTitleSafe(chatId)
        return messages.messages?.map { it.toScheduledMessage(chatTitle) } ?: emptyList()
    }

    override fun scheduleMessage(
        chatId: Long,
        text: String,
        sendAtEpochSeconds: Int,
        repeatPeriodSeconds: Int,
        disableNotification: Boolean,
        parseMode: ParseMode,
    ): ScheduledMessage {
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
        val message = awaitFinalMessage(withResilience("sendMessage") { send<TdApi.Message>(request) })
        return message.toScheduledMessage(getChatTitleSafe(chatId))
    }

    override fun rescheduleMessage(
        chatId: Long,
        messageId: Long,
        sendAtEpochSeconds: Int,
        repeatPeriodSeconds: Int,
    ): ScheduledMessage {
        log.info("Rescheduling message {} in chat {} for {}", messageId, chatId, sendAtEpochSeconds)
        val before = getScheduledMessages(chatId)
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
        return getScheduledMessages(chatId)
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

    override fun cancelScheduledMessage(chatId: Long, messageId: Long): Boolean {
        log.info("Cancelling scheduled message {} in chat {}", messageId, chatId)
        withResilience("deleteMessages") {
            send<TdApi.Ok>(TdApi.DeleteMessages(chatId, longArrayOf(messageId), true))
        }
        return true
    }

    // ── Chat management ────────────────────────────────────────────────────

    override fun createBasicGroup(title: String, userIds: List<Long>): ChatInfo {
        log.info("Creating basic group '{}' with {} members", title, userIds.size)

        val result = withResilience("createNewBasicGroupChat") {
            send<TdApi.CreatedBasicGroupChat>(TdApi.CreateNewBasicGroupChat(userIds.toLongArray(), title, 0))
        }
        val failedMembers = result.failedToAddMembers.toFailedMemberAdds()
        if (failedMembers.isNotEmpty()) {
            throw IllegalStateException(formatFailedToAddMembers("create_basic_group", result.chatId, failedMembers))
        }

        return getChat(result.chatId)
    }

    override fun addChatMembers(chatId: Long, userIds: List<Long>): Boolean {
        log.info("Adding {} members to chat {}", userIds.size, chatId)

        val result = withResilience("addChatMembers") {
            send<TdApi.FailedToAddMembers>(TdApi.AddChatMembers(chatId, userIds.toLongArray()))
        }
        val failedMembers = result.toFailedMemberAdds()
        if (failedMembers.isNotEmpty()) {
            throw IllegalStateException(formatFailedToAddMembers("add_chat_members", chatId, failedMembers))
        }
        return true
    }

    override fun leaveChat(chatId: Long): Boolean {
        log.info("Leaving chat {}", chatId)

        withResilience("leaveChat") { send<TdApi.Ok>(TdApi.LeaveChat(chatId)) }
        return true
    }

    override fun banChatMember(chatId: Long, userId: Long): Boolean {
        log.info("Banning user {} from chat {}", userId, chatId)

        val sender = TdApi.MessageSenderUser(userId)
        withResilience("banChatMember") {
            send<TdApi.Ok>(TdApi.BanChatMember(chatId, sender, 0, false))
        }
        return true
    }

    override fun unbanChatMember(chatId: Long, userId: Long): Boolean {
        log.info("Unbanning user {} from chat {}", userId, chatId)

        val sender = TdApi.MessageSenderUser(userId)
        // "Unban" means removing the banned status, not force-adding the user
        // back to the group. ChatMemberStatusMember fails with
        // USER_NOT_MUTUAL_CONTACT for perfectly valid non-contact members.
        val newStatus = TdApi.ChatMemberStatusLeft()
        withResilience("setChatMemberStatus") {
            send<TdApi.Ok>(TdApi.SetChatMemberStatus(chatId, sender, newStatus))
        }
        return true
    }

    override fun setChatMemberAdmin(chatId: Long, userId: Long, isAdmin: Boolean): Boolean {
        log.info("{} user {} in chat {}", if (isAdmin) "Promoting" else "Demoting", userId, chatId)

        val sender = TdApi.MessageSenderUser(userId)
        val newStatus = if (isAdmin) {
            TdApi.ChatMemberStatusAdministrator().apply {
                this.rights = TdApi.ChatAdministratorRights().apply {
                    this.canManageChat = true
                    this.canDeleteMessages = true
                    this.canManageVideoChats = true
                    this.canRestrictMembers = true
                    this.canPromoteMembers = false
                    this.canChangeInfo = true
                    this.canInviteUsers = true
                    this.canPinMessages = true
                }
            }
        } else {
            TdApi.ChatMemberStatusMember()
        }

        withResilience("setChatMemberStatus") {
            send<TdApi.Ok>(TdApi.SetChatMemberStatus(chatId, sender, newStatus))
        }
        return true
    }

    override fun getPrivacySettingRules(setting: PrivacySetting): PrivacySettingRules {
        log.info("Getting privacy setting rules for {}", setting)
        val result = withResilience("getUserPrivacySettingRules") {
            send<TdApi.UserPrivacySettingRules>(TdApi.GetUserPrivacySettingRules(tdPrivacySetting(setting)))
        }
        return PrivacySettingRules(setting, result.rules.map(::privacyRuleFromTd))
    }

    override fun setPrivacySettingRules(rules: PrivacySettingRules): Boolean {
        log.info("Setting privacy rules for {}", rules.setting)
        val tdRules = TdApi.UserPrivacySettingRules(rules.rules.map(::privacyRuleToTd).toTypedArray())
        withResilience("setUserPrivacySettingRules") {
            send<TdApi.Ok>(TdApi.SetUserPrivacySettingRules(tdPrivacySetting(rules.setting), tdRules))
        }
        return true
    }

    override fun getBotCommands(scope: BotCommandScope, languageCode: String): List<BotCommand> {
        log.info("Getting bot commands for scope {}", scope.kind)
        val result = withResilience("getCommands") {
            send<TdApi.BotCommands>(TdApi.GetCommands(tdBotCommandScope(scope), languageCode))
        }
        return result.commands.map { BotCommand(it.command, it.description) }
    }

    override fun setBotCommands(scope: BotCommandScope, languageCode: String, commands: List<BotCommand>): Boolean {
        log.info("Setting {} bot commands for scope {}", commands.size, scope.kind)
        val tdCommands = commands.map { TdApi.BotCommand(it.command, it.description) }.toTypedArray()
        withResilience("setCommands") {
            send<TdApi.Ok>(TdApi.SetCommands(tdBotCommandScope(scope), languageCode, tdCommands))
        }
        return true
    }

    override fun getChatPermissions(chatId: Long): ChatPermissions {
        log.info("Getting default permissions for chat {}", chatId)
        val chat = withResilience("getChat") { send<TdApi.Chat>(TdApi.GetChat(chatId)) }
        return chatPermissionsFromTd(chat.permissions)
    }

    override fun setChatPermissions(chatId: Long, permissions: ChatPermissions): Boolean {
        log.info("Setting default permissions for chat {}", chatId)
        withResilience("setChatPermissions") {
            send<TdApi.Ok>(TdApi.SetChatPermissions(chatId, chatPermissionsToTd(permissions)))
        }
        return true
    }

    override fun setChatMemberPermissions(
        chatId: Long,
        userId: Long,
        permissions: ChatPermissions,
        restrictedUntilDate: Int,
        isMember: Boolean,
    ): Boolean {
        log.info("Setting member permissions for user {} in chat {}", userId, chatId)
        val status = TdApi.ChatMemberStatusRestricted(isMember, restrictedUntilDate, chatPermissionsToTd(permissions))
        withResilience("setChatMemberStatus") {
            send<TdApi.Ok>(TdApi.SetChatMemberStatus(chatId, TdApi.MessageSenderUser(userId), status))
        }
        return true
    }

    override fun setChatMemberAdministratorRights(chatId: Long, userId: Long, rights: ChatAdministratorRights): Boolean {
        log.info("Setting administrator rights for user {} in chat {}", userId, chatId)
        val status = TdApi.ChatMemberStatusAdministrator(false, chatAdministratorRightsToTd(rights))
        withResilience("setChatMemberStatus") {
            send<TdApi.Ok>(TdApi.SetChatMemberStatus(chatId, TdApi.MessageSenderUser(userId), status))
        }
        return true
    }

    override fun setChatTitle(chatId: Long, title: String): Boolean {
        log.info("Setting title of chat {} to '{}'", chatId, title)

        withResilience("setChatTitle") {
            send<TdApi.Ok>(TdApi.SetChatTitle(chatId, title))
        }
        return true
    }

    override fun setChatDescription(chatId: Long, description: String): Boolean {
        log.info("Setting description of chat {} ({} chars)", chatId, description.length)

        withResilience("setChatDescription") {
            send<TdApi.Ok>(TdApi.SetChatDescription(chatId, description))
        }
        return true
    }

    override fun setChatSlowModeDelay(chatId: Long, delaySeconds: Int): Boolean {
        log.info("Setting slow-mode delay of chat {} to {}s", chatId, delaySeconds)

        withResilience("setChatSlowModeDelay") {
            send<TdApi.Ok>(TdApi.SetChatSlowModeDelay(chatId, delaySeconds))
        }
        return true
    }

    override fun archiveChat(chatId: Long): Boolean {
        log.info("Archiving chat {}", chatId)

        withResilience("addChatToList") {
            send<TdApi.Ok>(TdApi.AddChatToList(chatId, TdApi.ChatListArchive()))
        }
        return true
    }

    override fun unarchiveChat(chatId: Long): Boolean {
        log.info("Unarchiving chat {}", chatId)

        withResilience("addChatToList") {
            send<TdApi.Ok>(TdApi.AddChatToList(chatId, TdApi.ChatListMain()))
        }
        return true
    }

    override fun muteChat(chatId: Long, muteFor: Int): Boolean {
        log.info("Muting chat {} for {} seconds", chatId, muteFor)

        val settings = TdApi.ChatNotificationSettings().apply {
            this.muteFor = muteFor
            this.useDefaultMuteFor = false
        }

        withResilience("setChatNotificationSettings") {
            send<TdApi.Ok>(TdApi.SetChatNotificationSettings(chatId, settings))
        }
        return true
    }

    override fun unmuteChat(chatId: Long): Boolean {
        log.info("Unmuting chat {}", chatId)

        val settings = TdApi.ChatNotificationSettings().apply {
            this.muteFor = 0
            this.useDefaultMuteFor = true
        }

        withResilience("setChatNotificationSettings") {
            send<TdApi.Ok>(TdApi.SetChatNotificationSettings(chatId, settings))
        }
        return true
    }

    // ── Contact management ─────────────────────────────────────────────────

    override fun addContact(userId: Long, firstName: String, lastName: String?, phoneNumber: String?): Boolean {
        log.info("Adding contact: {} {} (userId={})", firstName, lastName ?: "", userId)

        val contact = TdApi.ImportedContact(
            phoneNumber ?: "",
            firstName,
            lastName ?: "",
            null,
        )

        withResilience("addContact") {
            send<TdApi.Ok>(TdApi.AddContact(userId, contact, false))
        }
        return true
    }

    override fun removeContacts(userIds: List<Long>): Boolean {
        log.info("Removing {} contacts", userIds.size)

        withResilience("removeContacts") {
            send<TdApi.Ok>(TdApi.RemoveContacts(userIds.toLongArray()))
        }
        return true
    }

    override fun blockUser(userId: Long): Boolean {
        log.info("Blocking user {}", userId)

        val sender = TdApi.MessageSenderUser(userId)
        withResilience("setMessageSenderBlockList") {
            send<TdApi.Ok>(TdApi.SetMessageSenderBlockList(sender, TdApi.BlockListMain()))
        }
        return true
    }

    override fun unblockUser(userId: Long): Boolean {
        log.info("Unblocking user {}", userId)

        val sender = TdApi.MessageSenderUser(userId)
        withResilience("setMessageSenderBlockList") {
            send<TdApi.Ok>(TdApi.SetMessageSenderBlockList(sender, null))
        }
        return true
    }

    // ── Entity resolution ───────────────────────────────────────────────────

    override fun resolveUsername(username: String): Long {
        log.info("Resolving username: @{}", username)

        val chat = withResilience("searchPublicChat") {
            send<TdApi.Chat>(TdApi.SearchPublicChat(username))
        }
        return chat.id
    }

    override fun resolvePhone(phoneNumber: String): Long {
        log.info("Resolving phone number")

        val user = withResilience("searchUserByPhoneNumber") {
            send<TdApi.User>(TdApi.SearchUserByPhoneNumber(phoneNumber, false))
        }
        return user.id
    }

    override fun resolveSelfChat(): Long {
        log.info("Resolving self chat")

        val me = withResilience("getMe") {
            send<TdApi.User>(TdApi.GetMe())
        }
        val chat = withResilience("createPrivateChat") {
            send<TdApi.Chat>(TdApi.CreatePrivateChat(me.id, true))
        }
        return chat.id
    }

    // ── Reactions & Polls ──────────────────────────────────────────────────

    override fun addReaction(chatId: Long, messageId: Long, emoji: String, isBig: Boolean): Boolean {
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

    override fun removeReaction(chatId: Long, messageId: Long, emoji: String): Boolean {
        log.info("Removing reaction '{}' from message {} in chat {}", emoji, messageId, chatId)

        val request = TdApi.RemoveMessageReaction(chatId, messageId, TdApi.ReactionTypeEmoji(emoji))
        withResilience("removeMessageReaction") { send<TdApi.Ok>(request) }
        return true
    }

    override fun getMessageReactions(chatId: Long, messageId: Long, limit: Int): MessageReactionSummary {
        log.info("Fetching reactions for message {} in chat {} (limit={})", messageId, chatId, limit)

        val request = TdApi.GetMessageAddedReactions().apply {
            this.chatId = chatId
            this.messageId = messageId
            this.reactionType = null
            this.offset = ""
            this.limit = limit.coerceIn(1, 100)
        }
        val result = withResilience("getMessageAddedReactions") {
            send<TdApi.AddedReactions>(request)
        }

        val reactions = result.reactions?.map { r ->
            val emoji = when (val t = r.type) {
                is TdApi.ReactionTypeEmoji -> t.emoji ?: ""
                is TdApi.ReactionTypeCustomEmoji -> "custom:${t.customEmojiId}"
                else -> t?.javaClass?.simpleName ?: "unknown"
            }
            val (senderId, senderName) = when (val s = r.senderId) {
                is TdApi.MessageSenderUser -> {
                    val user = getUserSafe(s.userId)
                    val name = if (user != null) {
                        listOfNotNull(user.firstName, user.lastName?.takeIf { it.isNotBlank() }).joinToString(" ")
                    } else {
                        "User(${s.userId})"
                    }
                    s.userId to name
                }
                is TdApi.MessageSenderChat -> {
                    val title = getChatTitleSafe(s.chatId)
                    s.chatId to title
                }
                else -> (null to null)
            }
            ReactionInfo(
                emoji = emoji,
                senderId = senderId,
                senderName = senderName,
                isOutgoing = r.isOutgoing,
                date = if (r.date > 0) Instant.ofEpochSecond(r.date.toLong()) else null,
            )
        } ?: emptyList()

        return MessageReactionSummary(chatId, messageId, reactions)
    }

    override fun sendPoll(
        chatId: Long,
        question: String,
        options: List<String>,
        isAnonymous: Boolean,
        allowMultipleAnswers: Boolean,
    ): TelegramMessage {
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

        val message = awaitFinalMessage(withResilience("sendMessage") { send<TdApi.Message>(request) })
        return message.toTelegramMessage(getChatTitleSafe(chatId))
    }

    override fun setPollAnswer(chatId: Long, messageId: Long, optionIds: List<Int>): Boolean {
        log.info("Voting in poll (message {} in chat {}) with {} option(s)", messageId, chatId, optionIds.size)

        withResilience("setPollAnswer") {
            send<TdApi.Ok>(TdApi.SetPollAnswer(chatId, messageId, optionIds.toIntArray()))
        }
        return true
    }

    override fun stopPoll(chatId: Long, messageId: Long): Boolean {
        log.info("Closing poll (message {} in chat {})", messageId, chatId)

        withResilience("stopPoll") {
            send<TdApi.Ok>(TdApi.StopPoll(chatId, messageId, null))
        }
        return true
    }

    override fun getMessageViewers(chatId: Long, messageId: Long): List<MessageViewerInfo> {
        log.info("Fetching viewers of message {} in chat {}", messageId, chatId)

        val viewers = withResilience("getMessageViewers") {
            send<TdApi.MessageViewers>(TdApi.GetMessageViewers(chatId, messageId))
        }
        return viewers.viewers?.map { viewer ->
            val user = getUserSafe(viewer.userId)
            val name = user?.let {
                listOfNotNull(it.firstName, it.lastName?.takeIf(String::isNotBlank)).joinToString(" ")
            }
            MessageViewerInfo(
                userId = viewer.userId,
                userName = name,
                viewDate = if (viewer.viewDate > 0) Instant.ofEpochSecond(viewer.viewDate.toLong()) else null,
            )
        } ?: emptyList()
    }

    override fun getMessageContext(chatId: Long, messageId: Long, contextSize: Int): List<TelegramMessage> {
        log.info("Fetching {} messages of context around message {} in chat {}", contextSize, messageId, chatId)

        val total = contextSize.coerceIn(1, 100)
        val beforeOffset = -(total / 2)
        val limit = total + 1

        val messages = withResilience("getChatHistory") {
            send<TdApi.Messages>(TdApi.GetChatHistory(chatId, messageId, beforeOffset, limit, false))
        }

        val chatTitle = getChatTitleSafe(chatId)
        return messages.messages?.map { it.toTelegramMessage(chatTitle) } ?: emptyList()
    }

    // ── Inline buttons & Forum topics ──────────────────────────────────────

    override fun listInlineButtons(chatId: Long, messageId: Long): List<InlineButtonInfo> {
        log.info("Inspecting inline buttons for message {} in chat {}", messageId, chatId)

        val messages = withResilience("getMessages") {
            send<TdApi.Messages>(TdApi.GetMessages(chatId, longArrayOf(messageId)))
        }
        val message = messages.messages?.firstOrNull()
            ?: throw IllegalStateException("Message $messageId not found in chat $chatId")

        val keyboard = message.replyMarkup as? TdApi.ReplyMarkupInlineKeyboard
            ?: return emptyList()

        val result = mutableListOf<InlineButtonInfo>()
        keyboard.rows?.forEachIndexed { row, buttons ->
            buttons?.forEachIndexed { idx, button ->
                if (button == null) return@forEachIndexed
                val (typeName, url, cbData) = when (val t = button.type) {
                    is TdApi.InlineKeyboardButtonTypeUrl -> Triple("url", t.url, null)
                    is TdApi.InlineKeyboardButtonTypeCallback -> Triple("callback", null, t.data?.let { String(it) })
                    is TdApi.InlineKeyboardButtonTypeCallbackWithPassword -> Triple("callback_with_password", null, t.data?.let { String(it) })
                    is TdApi.InlineKeyboardButtonTypeCallbackGame -> Triple("callback_game", null, null)
                    is TdApi.InlineKeyboardButtonTypeSwitchInline -> Triple("switch_inline", null, null)
                    is TdApi.InlineKeyboardButtonTypeLoginUrl -> Triple("login_url", t.url, null)
                    is TdApi.InlineKeyboardButtonTypeWebApp -> Triple("web_app", t.url, null)
                    else -> Triple(t?.javaClass?.simpleName ?: "unknown", null, null)
                }
                result += InlineButtonInfo(
                    row = row,
                    index = idx,
                    text = button.text ?: "",
                    type = typeName,
                    url = url,
                    callbackData = cbData,
                )
            }
        }
        return result
    }

    override fun pressInlineButton(
        chatId: Long,
        messageId: Long,
        buttonIndex: Int?,
        buttonText: String?,
    ): String {
        log.info("Pressing inline button (index={}, text={}) on message {} in chat {}", buttonIndex, buttonText, messageId, chatId)

        val buttons = listInlineButtons(chatId, messageId)
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

    override fun listForumTopics(chatId: Long, query: String, limit: Int): List<ForumTopicInfoModel> {
        log.info("Listing forum topics in chat {} (query='{}', limit={})", chatId, query, limit)

        val request = TdApi.GetForumTopics().apply {
            this.chatId = chatId
            this.query = query
            this.offsetDate = 0
            this.offsetMessageId = 0
            this.offsetForumTopicId = 0
            this.limit = limit.coerceIn(1, 100)
        }
        val result = withResilience("getForumTopics") { send<TdApi.ForumTopics>(request) }

        return result.topics?.mapNotNull { topic ->
            val info = topic.info ?: return@mapNotNull null
            info.toForumTopicInfoModel(isPinned = topic.isPinned, unreadCount = topic.unreadCount)
        } ?: emptyList()
    }

    override fun createForumTopic(
        chatId: Long,
        name: String,
        iconColor: Int?,
        customEmojiId: Long?,
    ): ForumTopicInfoModel {
        log.info("Creating forum topic '{}' in chat {}", name, chatId)

        val color = iconColor ?: DEFAULT_FORUM_TOPIC_ICON_COLOR
        require(color in ALLOWED_FORUM_TOPIC_ICON_COLORS) {
            "icon_color must be one of ${ALLOWED_FORUM_TOPIC_ICON_COLORS.joinToString { "0x${it.toString(16).uppercase()}" }}"
        }

        val request = TdApi.CreateForumTopic().apply {
            this.chatId = chatId
            this.name = name
            this.icon = TdApi.ForumTopicIcon(color, customEmojiId ?: 0L)
        }
        val info = withResilience("createForumTopic") { send<TdApi.ForumTopicInfo>(request) }
        return info.toForumTopicInfoModel(isPinned = false, unreadCount = 0)
    }

    override fun editForumTopic(
        chatId: Long,
        messageThreadId: Long,
        name: String?,
        editIconCustomEmoji: Boolean,
        customEmojiId: Long?,
    ): Boolean {
        log.info(
            "Editing forum topic {} in chat {} (nameSet={}, editIconCustomEmoji={})",
            messageThreadId,
            chatId,
            name != null,
            editIconCustomEmoji,
        )

        withResilience("editForumTopic") {
            send<TdApi.Ok>(
                TdApi.EditForumTopic(
                    chatId,
                    messageThreadId.toForumTopicId(),
                    name ?: "",
                    editIconCustomEmoji,
                    customEmojiId ?: 0L,
                ),
            )
        }
        return true
    }

    override fun closeForumTopic(chatId: Long, messageThreadId: Long): Boolean =
        setForumTopicClosed(chatId, messageThreadId, isClosed = true)

    override fun reopenForumTopic(chatId: Long, messageThreadId: Long): Boolean =
        setForumTopicClosed(chatId, messageThreadId, isClosed = false)

    private fun setForumTopicClosed(chatId: Long, messageThreadId: Long, isClosed: Boolean): Boolean {
        log.info("Setting forum topic {} in chat {} closed={}", messageThreadId, chatId, isClosed)

        withResilience("toggleForumTopicIsClosed") {
            send<TdApi.Ok>(TdApi.ToggleForumTopicIsClosed(chatId, messageThreadId.toForumTopicId(), isClosed))
        }
        return true
    }

    override fun setForumTopicsEnabled(chatId: Long, enabled: Boolean, hasForumTabs: Boolean): Boolean {
        log.info("Setting forum topics enabled={} for chat {}", enabled, chatId)

        val chat = withResilience("getChat") { send<TdApi.Chat>(TdApi.GetChat(chatId)) }
        val chatType = chat.type as? TdApi.ChatTypeSupergroup
            ?: throw IllegalStateException("chat_id must point to a supergroup")
        if (chatType.isChannel) {
            throw IllegalStateException("forum topics can be enabled only for supergroups, not channels")
        }

        withResilience("toggleSupergroupIsForum") {
            send<TdApi.Ok>(
                TdApi.ToggleSupergroupIsForum(
                    chatType.supergroupId,
                    enabled,
                    hasForumTabs,
                ),
            )
        }
        return true
    }

    // ── Channels & invite links ────────────────────────────────────────────

    override fun createSupergroupOrChannel(
        title: String,
        description: String,
        isSupergroup: Boolean,
        isForum: Boolean,
    ): ChatInfo {
        log.info("Creating {} '{}' (forum={})", if (isSupergroup) "supergroup" else "channel", title, isForum)

        val request = TdApi.CreateNewSupergroupChat().apply {
            this.title = title
            // Create a plain supergroup first, then enable forum mode after
            // the returned chat type is verified. This avoids native/API
            // schema skew turning the first boolean into isChannel=true.
            this.isForum = false
            this.isChannel = !isSupergroup
            this.description = description
            this.location = null
            this.messageAutoDeleteTime = 0
            this.forImport = false
        }
        val created = withResilience("createNewSupergroupChat") { send<TdApi.Chat>(request) }

        // TDLib occasionally returns the freshly-created Chat with a stale
        // `isChannel` flag on its ChatTypeSupergroup before the local cache is
        // fully up to date. Re-poll GetChat until the type matches what we
        // asked for, so callers (especially a host-side main-chat resolver)
        // see authoritative state and don't fall into a "wrong type → spam
        // recreate" loop.
        val refreshed = pollChatUntilSettled(
            chatId = created.id,
            expectChannel = !isSupergroup,
            attempts = CREATE_CHAT_REFRESH_ATTEMPTS,
            delayMs = CREATE_CHAT_REFRESH_DELAY_MS,
        ) ?: created
        val settledType = refreshed.type as? TdApi.ChatTypeSupergroup
            ?: throw IllegalStateException(
                "Telegram created chat ${created.id} with type ${refreshed.type?.javaClass?.simpleName ?: "unknown"}; " +
                    "expected ${if (isSupergroup) "supergroup" else "channel"}",
            )
        val expectedChannel = !isSupergroup
        if (settledType.isChannel != expectedChannel) {
            throw IllegalStateException(
                "Telegram created chat ${created.id} with isChannel=${settledType.isChannel}; " +
                    "expected isChannel=$expectedChannel for ${if (isSupergroup) "supergroup" else "channel"}",
            )
        }

        if (isSupergroup && isForum) {
            withResilience("toggleSupergroupIsForum") {
                send<TdApi.Ok>(
                    TdApi.ToggleSupergroupIsForum(
                        settledType.supergroupId,
                        true,
                        true,
                    ),
                )
            }
        }

        return refreshed.toChatInfo()
    }

    private fun pollChatUntilSettled(
        chatId: Long,
        expectChannel: Boolean,
        attempts: Int,
        delayMs: Long,
    ): TdApi.Chat? {
        var lastChat: TdApi.Chat? = null
        repeat(attempts) { attempt ->
            val chat = try {
                withResilience("getChat") { send<TdApi.Chat>(TdApi.GetChat(chatId)) }
            } catch (e: Exception) {
                log.warn("getChat for fresh chat {} failed (attempt {}): {}", chatId, attempt + 1, e.message)
                null
            }
            if (chat != null) {
                lastChat = chat
                val type = chat.type
                if (type is TdApi.ChatTypeSupergroup && type.isChannel == expectChannel) {
                    return chat
                }
            }
            if (attempt < attempts - 1) {
                try {
                    Thread.sleep(delayMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return lastChat
                }
            }
        }
        val finalChat = lastChat
        if (finalChat != null) {
            val type = finalChat.type
            if (type is TdApi.ChatTypeSupergroup && type.isChannel != expectChannel) {
                log.warn(
                    "Chat {} settled with isChannel={} after {} attempts; expected isChannel={}",
                    chatId, type.isChannel, attempts, expectChannel,
                )
            }
        }
        return finalChat
    }

    override fun createInviteLink(chatId: Long): ChatInviteLinkInfo {
        log.info("Creating invite link for chat {}", chatId)

        val request = TdApi.CreateChatInviteLink().apply {
            this.chatId = chatId
            this.name = ""
            this.expirationDate = 0
            this.memberLimit = 0
            this.createsJoinRequest = false
        }
        val link = withResilience("createChatInviteLink") { send<TdApi.ChatInviteLink>(request) }
        return link.toInviteLinkInfo(chatId)
    }

    override fun getChatInviteLinks(chatId: Long, includeRevoked: Boolean, limit: Int): List<ChatInviteLinkInfo> {
        log.info("Listing {} invite links for chat {}", if (includeRevoked) "revoked" else "active", chatId)

        val me = withResilience("getMe") { send<TdApi.User>(TdApi.GetMe()) }
        val request = TdApi.GetChatInviteLinks().apply {
            this.chatId = chatId
            this.creatorUserId = me.id
            this.isRevoked = includeRevoked
            this.offsetDate = 0
            this.offsetInviteLink = ""
            this.limit = limit.coerceIn(1, 100)
        }
        val links = withResilience("getChatInviteLinks") { send<TdApi.ChatInviteLinks>(request) }
        return links.inviteLinks?.map { it.toInviteLinkInfo(chatId) } ?: emptyList()
    }

    override fun revokeChatInviteLink(chatId: Long, inviteLink: String): List<ChatInviteLinkInfo> {
        log.info("Revoking an invite link for chat {}", chatId)

        val links = withResilience("revokeChatInviteLink") {
            send<TdApi.ChatInviteLinks>(TdApi.RevokeChatInviteLink(chatId, inviteLink))
        }
        return links.inviteLinks?.map { it.toInviteLinkInfo(chatId) } ?: emptyList()
    }

    private fun TdApi.ChatInviteLink.toInviteLinkInfo(chatId: Long): ChatInviteLinkInfo = ChatInviteLinkInfo(
        chatId = chatId,
        inviteLink = inviteLink ?: "",
        name = name?.takeIf { it.isNotBlank() },
        creatorUserId = creatorUserId,
        isPrimary = isPrimary,
        isRevoked = isRevoked,
        memberLimit = memberLimit,
        memberCount = memberCount,
        expireDate = if (expirationDate > 0) Instant.ofEpochSecond(expirationDate.toLong()) else null,
    )

    override fun joinChatByInviteLink(link: String): ChatInfo {
        log.info("Joining chat via invite link")

        val result = withResilience("joinChatByInviteLink") {
            send<TdApi.ChatJoinResult>(TdApi.JoinChatByInviteLink(link))
        }
        return when (result) {
            is TdApi.ChatJoinResultSuccess -> getChat(result.chatId)
            is TdApi.ChatJoinResultRequestSent ->
                throw IllegalStateException("Telegram sent a join request; administrator approval is required")
            is TdApi.ChatJoinResultDeclined ->
                throw IllegalStateException("Telegram declined the invite-link join request")
            is TdApi.ChatJoinResultGuardBotApprovalRequired ->
                throw IllegalStateException(
                    "Telegram requires approval by guard bot ${result.botUserId} before joining",
                )
        }
    }

    override fun joinPublicChat(usernameOrLink: String): ChatInfo {
        log.info("Joining public chat @{}", usernameOrLink)

        val normalized = usernameOrLink.removePrefix("@").trim()
        val chat = withResilience("searchPublicChat") {
            send<TdApi.Chat>(TdApi.SearchPublicChat(normalized))
        }
        withResilience("joinChat") { send<TdApi.Ok>(TdApi.JoinChat(chat.id)) }
        return chat.toChatInfo()
    }

    override fun getChatAdmins(chatId: Long, limit: Int): List<ChatMember> {
        log.info("Listing admins of chat {}", chatId)

        val members = withResilience("searchChatMembers") {
            send<TdApi.ChatMembers>(
                TdApi.SearchChatMembers(chatId, "", limit, TdApi.ChatMembersFilterAdministrators()),
            )
        }
        return members.members?.map { it.toChatMember() } ?: emptyList()
    }

    override fun getBannedChatMembers(chatId: Long, limit: Int): List<ChatMember> {
        log.info("Listing banned users of chat {}", chatId)

        val members = withResilience("searchChatMembers") {
            send<TdApi.ChatMembers>(
                TdApi.SearchChatMembers(chatId, "", limit, TdApi.ChatMembersFilterBanned()),
            )
        }
        return members.members?.map { it.toChatMember() } ?: emptyList()
    }

    // ── Voice, stickers & chat photo ───────────────────────────────────────

    override fun sendVoice(chatId: Long, filePath: String, duration: Int, caption: String?): TelegramMessage {
        log.info("Sending voice '{}' to chat {} (duration={}s)", filePath, chatId, duration)

        val content = TdApi.InputMessageVoiceNote().apply {
            this.voiceNote = TdApi.InputFileLocal(filePath)
            this.duration = duration.coerceAtLeast(0)
            this.waveform = ByteArray(0)
            this.caption = caption?.let { TdApi.FormattedText(it, emptyArray()) }
        }
        val request = TdApi.SendMessage().apply {
            this.chatId = chatId
            this.inputMessageContent = content
        }
        val message = awaitFinalMessage(withResilience("sendMessage") { send<TdApi.Message>(request) })
        return message.toTelegramMessage(getChatTitleSafe(chatId))
    }

    override fun sendSticker(chatId: Long, filePath: String, emoji: String?): TelegramMessage {
        log.info("Sending sticker '{}' to chat {}", filePath, chatId)

        val content = TdApi.InputMessageSticker().apply {
            this.sticker = TdApi.InputFileLocal(filePath)
            this.width = 0
            this.height = 0
            this.emoji = emoji ?: ""
        }
        val request = TdApi.SendMessage().apply {
            this.chatId = chatId
            this.inputMessageContent = content
        }
        val message = awaitFinalMessage(withResilience("sendMessage") { send<TdApi.Message>(request) })
        return message.toTelegramMessage(getChatTitleSafe(chatId))
    }

    override fun getInstalledStickerSets(limit: Int): List<StickerSetInfo> {
        log.info("Loading installed sticker sets (limit={})", limit)

        val result = withResilience("getInstalledStickerSets") {
            send<TdApi.StickerSets>(TdApi.GetInstalledStickerSets(TdApi.StickerTypeRegular()))
        }
        val capped = limit.coerceIn(1, 200)
        return result.sets
            ?.take(capped)
            ?.map { s ->
                StickerSetInfo(
                    id = s.id,
                    title = s.title ?: "",
                    name = s.name ?: "",
                    size = s.size,
                    isOfficial = s.isOfficial,
                    isArchived = s.isArchived,
                    isOwned = s.isOwned,
                )
            } ?: emptyList()
    }

    override fun setChatPhoto(chatId: Long, filePath: String): Boolean {
        log.info("Setting chat photo for chat {} from '{}'", chatId, filePath)

        val photo = TdApi.InputChatPhotoStatic(TdApi.InputFileLocal(filePath))
        withResilience("setChatPhoto") {
            send<TdApi.Ok>(TdApi.SetChatPhoto(chatId, photo))
        }
        return true
    }

    override fun deleteChatPhoto(chatId: Long): Boolean {
        log.info("Deleting chat photo for chat {}", chatId)

        withResilience("deleteChatPhoto") {
            send<TdApi.Ok>(TdApi.SetChatPhoto(chatId, null))
        }
        return true
    }

    // ── Drafts & profile ───────────────────────────────────────────────────

    override fun saveDraft(chatId: Long, text: String, replyToMessageId: Long?): Boolean {
        log.info("Saving draft on chat {} (replyTo={})", chatId, replyToMessageId)

        val inputText = TdApi.InputMessageText().apply {
            this.text = TdApi.FormattedText(text, emptyArray())
            this.clearDraft = false
        }
        val replyTo = replyToMessageId?.let {
            TdApi.InputMessageReplyToMessage().apply { this.messageId = it }
        }
        val draft = TdApi.DraftMessage().apply {
            this.replyTo = replyTo
            this.date = (System.currentTimeMillis() / 1000L).toInt()
            this.content = TdApi.DraftMessageContentText(inputText.text, null)
        }
        withResilience("setChatDraftMessage") {
            send<TdApi.Ok>(TdApi.SetChatDraftMessage(chatId, null, draft))
        }
        return true
    }

    override fun getDrafts(): List<DraftInfo> {
        log.info("Collecting chats with drafts")

        val chatIds = withResilience("getChats") {
            send<TdApi.Chats>(TdApi.GetChats(TdApi.ChatListMain(), 200))
        }.chatIds

        return chatIds.toList().mapNotNull { id ->
            val chat = try {
                withResilience("getChat") { send<TdApi.Chat>(TdApi.GetChat(id)) }
            } catch (_: Exception) {
                return@mapNotNull null
            }
            val draft = chat.draftMessage ?: return@mapNotNull null
            val text = (draft.content as? TdApi.DraftMessageContentText)?.text?.text ?: return@mapNotNull null
            if (text.isBlank()) return@mapNotNull null
            val replyTo = (draft.replyTo as? TdApi.InputMessageReplyToMessage)?.messageId
            DraftInfo(
                chatId = chat.id,
                chatTitle = chat.title,
                text = text,
                replyToMessageId = replyTo,
                date = if (draft.date > 0) Instant.ofEpochSecond(draft.date.toLong()) else null,
            )
        }
    }

    override fun clearDraft(chatId: Long): Boolean {
        log.info("Clearing draft on chat {}", chatId)

        withResilience("setChatDraftMessage") {
            send<TdApi.Ok>(TdApi.SetChatDraftMessage(chatId, null, null))
        }
        return true
    }

    override fun updateProfile(firstName: String?, lastName: String?, bio: String?): Boolean {
        log.info("Updating profile (firstName={}, lastName={}, bio set={})", firstName != null, lastName != null, bio != null)

        if (firstName != null || lastName != null) {
            val current = withResilience("getMe") { send<TdApi.User>(TdApi.GetMe()) }
            val f = firstName ?: current.firstName ?: ""
            val l = lastName ?: current.lastName ?: ""
            withResilience("setName") { send<TdApi.Ok>(TdApi.SetName(f, l)) }
        }
        if (bio != null) {
            withResilience("setBio") { send<TdApi.Ok>(TdApi.SetBio(bio)) }
        }
        return true
    }

    override fun setProfilePhoto(filePath: String): Boolean {
        log.info("Setting profile photo from '{}'", filePath)

        val photo = TdApi.InputChatPhotoStatic(TdApi.InputFileLocal(filePath))
        withResilience("setProfilePhoto") {
            send<TdApi.Ok>(TdApi.SetProfilePhoto(photo, false))
        }
        return true
    }

    override fun deleteProfilePhoto(photoId: Long?): Boolean {
        log.info("Deleting profile photo (id={})", photoId)

        val idToDelete = photoId ?: run {
            val photos = withResilience("getUserProfilePhotos") {
                val me = send<TdApi.User>(TdApi.GetMe())
                send<TdApi.ChatPhotos>(TdApi.GetUserProfilePhotos(me.id, 0, 1))
            }
            photos.photos?.firstOrNull()?.id
                ?: throw IllegalStateException("Current user has no profile photo to delete")
        }
        withResilience("deleteProfilePhoto") {
            send<TdApi.Ok>(TdApi.DeleteProfilePhoto(idToDelete))
        }
        return true
    }

    override fun getUserProfilePhotos(userId: Long, limit: Int): List<UserPhotoInfo> {
        log.info("Fetching profile photos for user {} (limit={})", userId, limit)

        val capped = limit.coerceIn(1, 100)
        val photos = withResilience("getUserProfilePhotos") {
            send<TdApi.ChatPhotos>(TdApi.GetUserProfilePhotos(userId, 0, capped))
        }
        return photos.photos?.map { p ->
            val largest = p.sizes?.maxByOrNull { it.width * it.height }
            UserPhotoInfo(
                photoId = p.id,
                addedDate = if (p.addedDate > 0) Instant.ofEpochSecond(p.addedDate.toLong()) else null,
                hasAnimation = p.animation != null || p.smallAnimation != null,
                width = largest?.width,
                height = largest?.height,
                fileSize = largest?.photo?.size,
            )
        } ?: emptyList()
    }

    // ── Extended search, contacts & administration ────────────────────────

    override fun searchPublicChats(query: String, limit: Int): List<ChatInfo> {
        log.info("Searching public chats for '{}' (limit={})", query, limit)

        val chats = withResilience("searchPublicChats") {
            send<TdApi.Chats>(TdApi.SearchPublicChats(query, null))
        }
        val capped = limit.coerceIn(1, 100)
        return chats.chatIds.take(capped).mapNotNull { id ->
            try {
                val chat = withResilience("getChat") { send<TdApi.Chat>(TdApi.GetChat(id)) }
                // SearchPublicChats only returns chats with a public username, but
                // TdApi.Chat itself doesn't expose usernames — they live on the
                // underlying Supergroup/User object. Resolve them here so callers
                // (e.g. public-search allowlist gates) can match by @handle.
                val username: String? = when (val ct = chat.type) {
                    is TdApi.ChatTypeSupergroup -> try {
                        withResilience("getSupergroup") {
                            send<TdApi.Supergroup>(TdApi.GetSupergroup(ct.supergroupId))
                        }.usernames?.activeUsernames?.firstOrNull()
                    } catch (_: Exception) { null }
                    is TdApi.ChatTypePrivate -> try {
                        withResilience("getUser") {
                            send<TdApi.User>(TdApi.GetUser(ct.userId))
                        }.usernames?.activeUsernames?.firstOrNull()
                    } catch (_: Exception) { null }
                    else -> null
                }
                chat.toChatInfo().copy(username = username)
            } catch (_: Exception) {
                null
            }
        }
    }

    override fun searchContactsByQuery(query: String, limit: Int): List<ContactInfo> {
        log.info("Searching contacts for '{}' (limit={})", query, limit)

        val capped = limit.coerceIn(1, 200)
        val users = withResilience("searchContacts") {
            send<TdApi.Users>(TdApi.SearchContacts(query, capped))
        }
        return users.userIds.toList().mapNotNull { id ->
            try {
                val u = withResilience("getUser") { send<TdApi.User>(TdApi.GetUser(id)) }
                ContactInfo(
                    userId = u.id,
                    firstName = u.firstName ?: "",
                    lastName = u.lastName?.takeIf { it.isNotBlank() },
                    username = u.usernames?.activeUsernames?.firstOrNull(),
                    phoneNumber = u.phoneNumber?.takeIf { it.isNotBlank() },
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    override fun getBlockedUsers(limit: Int): List<UserInfo> {
        log.info("Fetching blocked users (limit={})", limit)

        val capped = limit.coerceIn(1, 200)
        val senders = withResilience("getBlockedMessageSenders") {
            send<TdApi.MessageSenders>(TdApi.GetBlockedMessageSenders(TdApi.BlockListMain(), 0, capped))
        }
        return senders.senders?.mapNotNull { s ->
            val userId = when (s) {
                is TdApi.MessageSenderUser -> s.userId
                else -> return@mapNotNull null
            }
            try {
                withResilience("getUser") { send<TdApi.User>(TdApi.GetUser(userId)) }.toUserInfo()
            } catch (_: Exception) {
                null
            }
        } ?: emptyList()
    }

    override fun getUserStatus(userId: Long): String {
        log.info("Getting user status for {}", userId)

        val user = withResilience("getUser") { send<TdApi.User>(TdApi.GetUser(userId)) }
        return when (val st = user.status) {
            is TdApi.UserStatusOnline -> "online"
            is TdApi.UserStatusOffline -> "offline (last seen ${Instant.ofEpochSecond(st.wasOnline.toLong())})"
            is TdApi.UserStatusRecently -> "recently"
            is TdApi.UserStatusLastWeek -> "last week"
            is TdApi.UserStatusLastMonth -> "last month"
            else -> "unknown"
        }
    }

    override fun getChatEventLog(chatId: Long, limit: Int): List<AdminActionInfo> {
        log.info("Fetching event log for chat {} (limit={})", chatId, limit)

        val capped = limit.coerceIn(1, 100)
        val request = TdApi.GetChatEventLog().apply {
            this.chatId = chatId
            this.query = ""
            this.fromEventId = 0
            this.limit = capped
            this.filters = null
            this.userIds = LongArray(0)
        }
        val events = withResilience("getChatEventLog") { send<TdApi.ChatEvents>(request) }

        return events.events?.map { ev ->
            val actorUserId = (ev.memberId as? TdApi.MessageSenderUser)?.userId
            val actorName = actorUserId?.let {
                try {
                    val u = withResilience("getUser") { send<TdApi.User>(TdApi.GetUser(it)) }
                    listOfNotNull(u.firstName, u.lastName?.takeIf { n -> n.isNotBlank() }).joinToString(" ")
                } catch (_: Exception) {
                    null
                }
            }
            val actionName = ev.action?.javaClass?.simpleName?.removePrefix("ChatEvent") ?: "Unknown"
            val details = when (val a = ev.action) {
                is TdApi.ChatEventMessageDeleted -> a.message?.let { "deleted msg ${it.id}" }
                is TdApi.ChatEventMessageEdited -> a.newMessage?.let { "edited msg ${it.id}" }
                is TdApi.ChatEventMessagePinned -> a.message?.let { "pinned msg ${it.id}" }
                is TdApi.ChatEventMessageUnpinned -> a.message?.let { "unpinned msg ${it.id}" }
                is TdApi.ChatEventTitleChanged -> "'${a.oldTitle}' → '${a.newTitle}'"
                is TdApi.ChatEventDescriptionChanged -> "description changed"
                is TdApi.ChatEventMemberPromoted -> "promoted user ${a.userId}"
                is TdApi.ChatEventMemberInvited -> "invited user ${a.userId}"
                else -> null
            }
            AdminActionInfo(
                eventId = ev.id,
                date = Instant.ofEpochSecond(ev.date.toLong()),
                actorUserId = actorUserId,
                actorName = actorName,
                action = actionName,
                details = details,
            )
        } ?: emptyList()
    }

    // ── Utilities ──────────────────────────────────────────────────────────

    override fun getMessageByLink(link: String): TelegramMessage {
        log.info("Resolving message link")

        val info = withResilience("getMessageLinkInfo") {
            send<TdApi.MessageLinkInfo>(TdApi.GetMessageLinkInfo(link))
        }
        val message = info.message
            ?: throw IllegalStateException("Link does not resolve to a specific message")
        val chatTitle = getChatTitleSafe(info.chatId)
        return message.toTelegramMessage(chatTitle)
    }

    override fun getMessageLink(
        chatId: Long,
        messageId: Long,
        forAlbum: Boolean,
        inMessageThread: Boolean,
    ): TelegramMessageLink {
        log.info("Creating message link for message {} in chat {}", messageId, chatId)

        val result = withResilience("getMessageLink") {
            send<TdApi.MessageLink>(
                TdApi.GetMessageLink(
                    chatId,
                    messageId,
                    0,
                    0,
                    "",
                    forAlbum,
                    inMessageThread,
                ),
            )
        }
        return TelegramMessageLink(
            chatId = chatId,
            messageId = messageId,
            link = result.link,
            isPublic = result.isPublic,
        )
    }

    override fun getLastInteractionWithContact(contactId: Long): TelegramMessage? {
        log.info("Fetching last interaction with contact {}", contactId)

        val chat = withResilience("createPrivateChat") {
            send<TdApi.Chat>(TdApi.CreatePrivateChat(contactId, true))
        }
        val last = chat.lastMessage ?: return null
        return last.toTelegramMessage(chat.title)
    }

    override fun getGroupsInCommon(userId: Long, limit: Int): List<ChatInfo> {
        log.info("Fetching groups in common with user {} (limit={})", userId, limit)

        val chats = withResilience("getGroupsInCommon") {
            send<TdApi.Chats>(TdApi.GetGroupsInCommon(userId, 0, limit.coerceIn(1, 100)))
        }
        return chats.chatIds?.toList().orEmpty().mapNotNull { chatId ->
            try {
                withResilience("getChat") { send<TdApi.Chat>(TdApi.GetChat(chatId)) }.toChatInfo()
            } catch (_: Exception) {
                null
            }
        }
    }

    // ── Internal helpers ────────────────────────────────────────────────────

    private fun tdPrivacySetting(setting: PrivacySetting): TdApi.UserPrivacySetting = when (setting) {
        PrivacySetting.SHOW_STATUS -> TdApi.UserPrivacySettingShowStatus()
        PrivacySetting.SHOW_PROFILE_PHOTO -> TdApi.UserPrivacySettingShowProfilePhoto()
        PrivacySetting.SHOW_PHONE_NUMBER -> TdApi.UserPrivacySettingShowPhoneNumber()
        PrivacySetting.SHOW_LINK_IN_FORWARDED_MESSAGES -> TdApi.UserPrivacySettingShowLinkInForwardedMessages()
        PrivacySetting.ALLOW_CHAT_INVITES -> TdApi.UserPrivacySettingAllowChatInvites()
        PrivacySetting.ALLOW_CALLS -> TdApi.UserPrivacySettingAllowCalls()
        PrivacySetting.ALLOW_PEER_TO_PEER_CALLS -> TdApi.UserPrivacySettingAllowPeerToPeerCalls()
        PrivacySetting.ALLOW_PRIVATE_VOICE_AND_VIDEO_NOTES -> TdApi.UserPrivacySettingAllowPrivateVoiceAndVideoNoteMessages()
    }

    private fun privacyRuleFromTd(rule: TdApi.UserPrivacySettingRule): PrivacyRule = when (rule) {
        is TdApi.UserPrivacySettingRuleAllowAll -> PrivacyRule(PrivacyRuleKind.ALLOW_ALL)
        is TdApi.UserPrivacySettingRuleRestrictAll -> PrivacyRule(PrivacyRuleKind.RESTRICT_ALL)
        is TdApi.UserPrivacySettingRuleAllowContacts -> PrivacyRule(PrivacyRuleKind.ALLOW_CONTACTS)
        is TdApi.UserPrivacySettingRuleRestrictContacts -> PrivacyRule(PrivacyRuleKind.RESTRICT_CONTACTS)
        is TdApi.UserPrivacySettingRuleAllowUsers -> PrivacyRule(PrivacyRuleKind.ALLOW_USERS, rule.userIds.toList())
        is TdApi.UserPrivacySettingRuleRestrictUsers -> PrivacyRule(PrivacyRuleKind.RESTRICT_USERS, rule.userIds.toList())
        is TdApi.UserPrivacySettingRuleAllowChatMembers -> PrivacyRule(PrivacyRuleKind.ALLOW_CHAT_MEMBERS, chatIds = rule.chatIds.toList())
        is TdApi.UserPrivacySettingRuleRestrictChatMembers -> PrivacyRule(PrivacyRuleKind.RESTRICT_CHAT_MEMBERS, chatIds = rule.chatIds.toList())
        is TdApi.UserPrivacySettingRuleAllowBots -> PrivacyRule(PrivacyRuleKind.ALLOW_BOTS)
        is TdApi.UserPrivacySettingRuleRestrictBots -> PrivacyRule(PrivacyRuleKind.RESTRICT_BOTS)
        is TdApi.UserPrivacySettingRuleAllowPremiumUsers -> PrivacyRule(PrivacyRuleKind.ALLOW_PREMIUM_USERS)
    }

    private fun privacyRuleToTd(rule: PrivacyRule): TdApi.UserPrivacySettingRule = when (rule.kind) {
        PrivacyRuleKind.ALLOW_ALL -> TdApi.UserPrivacySettingRuleAllowAll()
        PrivacyRuleKind.RESTRICT_ALL -> TdApi.UserPrivacySettingRuleRestrictAll()
        PrivacyRuleKind.ALLOW_CONTACTS -> TdApi.UserPrivacySettingRuleAllowContacts()
        PrivacyRuleKind.RESTRICT_CONTACTS -> TdApi.UserPrivacySettingRuleRestrictContacts()
        PrivacyRuleKind.ALLOW_USERS -> TdApi.UserPrivacySettingRuleAllowUsers(rule.userIds.toLongArray())
        PrivacyRuleKind.RESTRICT_USERS -> TdApi.UserPrivacySettingRuleRestrictUsers(rule.userIds.toLongArray())
        PrivacyRuleKind.ALLOW_CHAT_MEMBERS -> TdApi.UserPrivacySettingRuleAllowChatMembers(rule.chatIds.toLongArray())
        PrivacyRuleKind.RESTRICT_CHAT_MEMBERS -> TdApi.UserPrivacySettingRuleRestrictChatMembers(rule.chatIds.toLongArray())
        PrivacyRuleKind.ALLOW_BOTS -> TdApi.UserPrivacySettingRuleAllowBots()
        PrivacyRuleKind.RESTRICT_BOTS -> TdApi.UserPrivacySettingRuleRestrictBots()
        PrivacyRuleKind.ALLOW_PREMIUM_USERS -> TdApi.UserPrivacySettingRuleAllowPremiumUsers()
    }

    private fun tdBotCommandScope(scope: BotCommandScope): TdApi.BotCommandScope = when (scope.kind) {
        BotCommandScopeKind.DEFAULT -> TdApi.BotCommandScopeDefault()
        BotCommandScopeKind.ALL_PRIVATE_CHATS -> TdApi.BotCommandScopeAllPrivateChats()
        BotCommandScopeKind.ALL_GROUP_CHATS -> TdApi.BotCommandScopeAllGroupChats()
        BotCommandScopeKind.ALL_CHAT_ADMINISTRATORS -> TdApi.BotCommandScopeAllChatAdministrators()
        BotCommandScopeKind.CHAT -> TdApi.BotCommandScopeChat(scope.chatId ?: throw IllegalArgumentException("chat_id is required for CHAT scope"))
        BotCommandScopeKind.CHAT_ADMINISTRATORS -> TdApi.BotCommandScopeChatAdministrators(scope.chatId ?: throw IllegalArgumentException("chat_id is required for CHAT_ADMINISTRATORS scope"))
        BotCommandScopeKind.CHAT_MEMBER -> TdApi.BotCommandScopeChatMember(
            scope.chatId ?: throw IllegalArgumentException("chat_id is required for CHAT_MEMBER scope"),
            scope.userId ?: throw IllegalArgumentException("user_id is required for CHAT_MEMBER scope"),
        )
    }

    private fun chatPermissionsFromTd(permissions: TdApi.ChatPermissions?): ChatPermissions {
        val value = permissions ?: return ChatPermissions()
        return ChatPermissions(
            value.canSendBasicMessages, value.canSendAudios, value.canSendDocuments, value.canSendPhotos,
            value.canSendVideos, value.canSendVideoNotes, value.canSendVoiceNotes, value.canSendPolls,
            value.canSendOtherMessages, value.canAddLinkPreviews, value.canReactToMessages, value.canEditTag,
            value.canChangeInfo, value.canInviteUsers, value.canPinMessages, value.canCreateTopics,
        )
    }

    private fun chatPermissionsToTd(permissions: ChatPermissions): TdApi.ChatPermissions = TdApi.ChatPermissions(
        permissions.canSendBasicMessages, permissions.canSendAudios, permissions.canSendDocuments, permissions.canSendPhotos,
        permissions.canSendVideos, permissions.canSendVideoNotes, permissions.canSendVoiceNotes, permissions.canSendPolls,
        permissions.canSendOtherMessages, permissions.canAddLinkPreviews, permissions.canReactToMessages, permissions.canEditTag,
        permissions.canChangeInfo, permissions.canInviteUsers, permissions.canPinMessages, permissions.canCreateTopics,
    )

    private fun chatAdministratorRightsToTd(rights: ChatAdministratorRights): TdApi.ChatAdministratorRights = TdApi.ChatAdministratorRights(
        rights.canManageChat, rights.canChangeInfo, rights.canPostMessages, rights.canEditMessages,
        rights.canDeleteMessages, rights.canInviteUsers, rights.canRestrictMembers, rights.canPinMessages,
        rights.canManageTopics, rights.canPromoteMembers, rights.canManageVideoChats, rights.canPostStories,
        rights.canEditStories, rights.canDeleteStories, rights.canManageDirectMessages, rights.canManageTags,
        rights.isAnonymous,
    )

    /**
     * Sends a TDLib request synchronously by bridging the async callback API
     * with a [CompletableFuture].
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T : TdApi.Object> send(function: TdApi.Function<*>): T {
        val future = CompletableFuture<TdApi.Object>()
        client.send(function) { result ->
            try {
                future.complete(result.get())
            } catch (e: Exception) {
                future.completeExceptionally(e)
            }
        }
        return try {
            future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) as T
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        }
    }

    private fun awaitFinalMessage(message: TdApi.Message): TdApi.Message =
        messageSendTracker?.awaitFinal(message, MESSAGE_SEND_TIMEOUT_SECONDS) ?: message

    /**
     * Wraps a block with Resilience4j rate limiter + circuit breaker.
     */
    private fun <T> withResilience(operation: String, block: () -> T): T {
        return try {
            val decorated = CircuitBreaker.decorateSupplier(circuitBreaker) {
                RateLimiter.decorateSupplier(rateLimiter, block).get()
            }
            decorated.get()
        } catch (ex: RequestNotPermitted) {
            meterRegistry.counter("telegram.api.rate_limited", "operation", operation).increment()
            throw TelegramUnavailableException(ex)
        } catch (ex: io.github.resilience4j.circuitbreaker.CallNotPermittedException) {
            meterRegistry.counter("telegram.api.circuit_open", "operation", operation).increment()
            throw TelegramUnavailableException(ex)
        }
    }

    private fun formatText(text: String, parseMode: ParseMode): TdApi.FormattedText {
        val tdParseMode = TextFormatter.toTdLibParseMode(parseMode)
        if (tdParseMode == null) {
            return TextFormatter.plainText(text)
        }
        return try {
            send(TdApi.ParseTextEntities(text, tdParseMode))
        } catch (_: Exception) {
            log.warn("Failed to parse text as {}, falling back to plain text", parseMode)
            TextFormatter.plainText(text)
        }
    }

    private fun getChatTitleSafe(chatId: Long): String? {
        return try {
            withResilience("getChat") { send<TdApi.Chat>(TdApi.GetChat(chatId)) }.title
        } catch (_: Exception) {
            null
        }
    }

    private fun getUserSafe(userId: Long): TdApi.User? {
        return try {
            withResilience("getUser") { send<TdApi.User>(TdApi.GetUser(userId)) }
        } catch (_: Exception) {
            null
        }
    }

    // ── Mappers: TDLib → domain models ──────────────────────────────────────

    private fun TdApi.Message.toTelegramMessage(chatTitle: String? = null): TelegramMessage {
        val senderName = when (val sender = this.senderId) {
            is TdApi.MessageSenderUser -> {
                val user = getUserSafe(sender.userId)
                if (user != null) {
                    listOfNotNull(user.firstName, user.lastName).joinToString(" ")
                } else {
                    "User(${sender.userId})"
                }
            }
            is TdApi.MessageSenderChat -> {
                getChatTitleSafe(sender.chatId) ?: run {
                    "Chat(${sender.chatId})"
                }
            }
            else -> "Unknown"
        }

        val senderId = when (val sender = this.senderId) {
            is TdApi.MessageSenderUser -> sender.userId
            is TdApi.MessageSenderChat -> sender.chatId
            else -> null
        }

        val text = when (val content = this.content) {
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
            else -> "[${content?.javaClass?.simpleName ?: "Unknown"}]"
        }

        val mediaType = when (this.content) {
            is TdApi.MessageText -> null
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
            else -> "other"
        }

        val replyToId = when (val reply = this.replyTo) {
            is TdApi.MessageReplyToMessage -> reply.messageId
            else -> null
        }

        return TelegramMessage(
            messageId = this.id,
            chatId = this.chatId,
            chatTitle = chatTitle,
            senderName = senderName,
            senderId = senderId,
            text = text,
            date = Instant.ofEpochSecond(this.date.toLong()),
            replyToMessageId = replyToId,
            messageThreadId = this.topicId.toMessageThreadId(),
            mediaType = mediaType,
            poll = (this.content as? TdApi.MessagePoll)?.poll?.toTelegramPoll(),
        )
    }

    private fun TdApi.Poll.toTelegramPoll(): TelegramPoll = TelegramPoll(
        question = question?.text.orEmpty(),
        totalVoterCount = totalVoterCount,
        isAnonymous = isAnonymous,
        isClosed = isClosed,
        options = options?.map { option -> option.toTelegramPollOption() } ?: emptyList(),
    )

    private fun TdApi.Message.toScheduledMessage(chatTitle: String?): ScheduledMessage {
        val state = schedulingState
        return when (state) {
            is TdApi.MessageSchedulingStateSendAtDate -> ScheduledMessage(
                message = toTelegramMessage(chatTitle),
                sendAt = Instant.ofEpochSecond(state.sendDate.toLong()),
                repeatPeriodSeconds = state.repeatPeriod,
            )
            is TdApi.MessageSchedulingStateSendWhenOnline -> ScheduledMessage(
                message = toTelegramMessage(chatTitle),
                sendWhenOnline = true,
            )
            else -> ScheduledMessage(message = toTelegramMessage(chatTitle))
        }
    }

    private fun TdApi.ChatFolderInfo.toChatFolderInfo(): ChatFolderInfo = ChatFolderInfo(
        id = id,
        title = name?.text?.text.orEmpty(),
        iconName = icon?.name?.takeIf { it.isNotBlank() },
        colorId = colorId,
        isShareable = isShareable,
        hasMyInviteLinks = hasMyInviteLinks,
    )

    private fun TdApi.ChatFolder.toChatFolderDefinition(): ChatFolderDefinition = ChatFolderDefinition(
        title = name?.text?.text.orEmpty(),
        iconName = icon?.name?.takeIf { it.isNotBlank() },
        colorId = colorId,
        isShareable = isShareable,
        pinnedChatIds = pinnedChatIds?.toList().orEmpty(),
        includedChatIds = includedChatIds?.toList().orEmpty(),
        excludedChatIds = excludedChatIds?.toList().orEmpty(),
        excludeMuted = excludeMuted,
        excludeRead = excludeRead,
        excludeArchived = excludeArchived,
        includeContacts = includeContacts,
        includeNonContacts = includeNonContacts,
        includeBots = includeBots,
        includeGroups = includeGroups,
        includeChannels = includeChannels,
    )

    private fun ChatFolderDefinition.toTdLib(): TdApi.ChatFolder = TdApi.ChatFolder(
        TdApi.ChatFolderName(TdApi.FormattedText(title, emptyArray()), false),
        TdApi.ChatFolderIcon(iconName.orEmpty()),
        colorId,
        isShareable,
        pinnedChatIds.toLongArray(),
        includedChatIds.toLongArray(),
        excludedChatIds.toLongArray(),
        excludeMuted,
        excludeRead,
        excludeArchived,
        includeContacts,
        includeNonContacts,
        includeBots,
        includeGroups,
        includeChannels,
    )

    private fun TdApi.PollOption.toTelegramPollOption(): TelegramPollOption = TelegramPollOption(
        text = text?.text.orEmpty(),
        voterCount = voterCount,
        votePercentage = votePercentage,
        isChosen = isChosen,
    )

    private fun TdApi.Chat.toChatInfo(fullInfo: TdApi.Object? = null): ChatInfo {
        val chatType = when (this.type) {
            is TdApi.ChatTypePrivate -> ChatType.PRIVATE
            is TdApi.ChatTypeBasicGroup -> ChatType.GROUP
            is TdApi.ChatTypeSupergroup -> {
                if ((this.type as TdApi.ChatTypeSupergroup).isChannel) ChatType.CHANNEL
                else ChatType.SUPERGROUP
            }
            is TdApi.ChatTypeSecret -> ChatType.SECRET
            else -> ChatType.PRIVATE
        }

        val memberCount = when (fullInfo) {
            is TdApi.SupergroupFullInfo -> fullInfo.memberCount
            is TdApi.BasicGroupFullInfo -> fullInfo.members?.size
            else -> null
        }

        val description = when (fullInfo) {
            is TdApi.SupergroupFullInfo -> fullInfo.description?.takeIf { it.isNotBlank() }
            is TdApi.BasicGroupFullInfo -> fullInfo.description?.takeIf { it.isNotBlank() }
            else -> null
        }

        val lastMsgDate = this.lastMessage?.date?.toLong()?.let { Instant.ofEpochSecond(it) }

        return ChatInfo(
            chatId = this.id,
            title = this.title,
            type = chatType,
            memberCount = memberCount,
            description = description,
            unreadCount = this.unreadCount,
            lastMessageDate = lastMsgDate,
            hasPhoto = this.photo != null,
        )
    }

    private fun TdApi.ForumTopicInfo.toForumTopicInfoModel(isPinned: Boolean, unreadCount: Int): ForumTopicInfoModel =
        ForumTopicInfoModel(
            chatId = chatId,
            topicId = forumTopicId.toLong(),
            messageThreadId = forumTopicId.toLong(),
            name = name ?: "",
            isGeneral = isGeneral,
            isClosed = isClosed,
            isPinned = isPinned,
            unreadCount = unreadCount,
            creationDate = if (creationDate > 0) Instant.ofEpochSecond(creationDate.toLong()) else null,
            iconColor = icon?.color,
            customEmojiId = icon?.customEmojiId?.takeIf { it != 0L },
        )

    private fun TdApi.User.toUserInfo(): UserInfo {
        val statusText = when (this.status) {
            is TdApi.UserStatusOnline -> "online"
            is TdApi.UserStatusOffline -> {
                val ts = (this.status as TdApi.UserStatusOffline).wasOnline
                "offline (last seen ${Instant.ofEpochSecond(ts.toLong())})"
            }
            is TdApi.UserStatusRecently -> "recently"
            is TdApi.UserStatusLastWeek -> "last week"
            is TdApi.UserStatusLastMonth -> "last month"
            else -> "unknown"
        }

        // Try to fetch full info (bio) — ignore errors
        val bio = try {
            val fullInfo = send<TdApi.UserFullInfo>(TdApi.GetUserFullInfo(this.id))
            fullInfo.bio?.text?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }

        return UserInfo(
            userId = this.id,
            firstName = this.firstName,
            lastName = this.lastName?.takeIf { it.isNotBlank() },
            username = this.usernames?.activeUsernames?.firstOrNull(),
            phoneNumber = this.phoneNumber?.takeIf { it.isNotBlank() },
            isBot = this.type is TdApi.UserTypeBot,
            isContact = this.isContact,
            isPremium = this.isPremium,
            bio = bio,
            lastSeenStatus = statusText,
        )
    }

    private fun TdApi.Message.toMediaInfo(chatId: Long): MediaInfo {
        val (mediaType, fileName, mimeType, fileSize, width, height, duration) = when (val content = this.content) {
            is TdApi.MessagePhoto -> {
                val largest = content.photo?.sizes?.maxByOrNull { it.width * it.height }
                MediaData("photo", null, "image/jpeg", largest?.photo?.size, largest?.width, largest?.height, null)
            }
            is TdApi.MessageVideo -> {
                val v = content.video
                MediaData("video", v?.fileName, v?.mimeType, v?.video?.size, v?.width, v?.height, v?.duration)
            }
            is TdApi.MessageDocument -> {
                val d = content.document
                MediaData("document", d?.fileName, d?.mimeType, d?.document?.size, null, null, null)
            }
            is TdApi.MessageAudio -> {
                val a = content.audio
                MediaData("audio", a?.fileName, a?.mimeType, a?.audio?.size, null, null, a?.duration)
            }
            is TdApi.MessageVoiceNote -> {
                val v = content.voiceNote
                MediaData("voice", null, v?.mimeType, v?.voice?.size, null, null, v?.duration)
            }
            is TdApi.MessageSticker -> {
                val s = content.sticker
                MediaData("sticker", null, null, s?.sticker?.size, s?.width, s?.height, null)
            }
            is TdApi.MessageAnimation -> {
                val a = content.animation
                MediaData("animation", a?.fileName, a?.mimeType, a?.animation?.size, a?.width, a?.height, a?.duration)
            }
            is TdApi.MessageVideoNote -> {
                val v = content.videoNote
                MediaData("video_note", null, null, v?.video?.size, v?.length, v?.length, v?.duration)
            }
            else -> MediaData("unknown", null, null, null, null, null, null)
        }

        val caption = when (val content = this.content) {
            is TdApi.MessagePhoto -> content.caption?.text
            is TdApi.MessageVideo -> content.caption?.text
            is TdApi.MessageDocument -> content.caption?.text
            is TdApi.MessageAudio -> content.caption?.text
            is TdApi.MessageVoiceNote -> content.caption?.text
            is TdApi.MessageAnimation -> content.caption?.text
            else -> null
        }

        return MediaInfo(
            messageId = this.id,
            chatId = chatId,
            mediaType = mediaType,
            fileName = fileName,
            mimeType = mimeType,
            fileSize = fileSize,
            width = width,
            height = height,
            duration = duration,
            caption = caption?.takeIf { it.isNotBlank() },
        )
    }

    /** Telegram forum threads use the bounded forum-topic branch of the topic union. */
    private fun forumThreadTopic(messageThreadId: Long): TdApi.MessageTopic =
        TdApi.MessageTopicForum(messageThreadId.toForumTopicId())

    /** Forum-management endpoints now use a bounded integer topic ID. */
    private fun Long.toForumTopicId(): Int =
        toInt().also { topicId ->
            require(topicId.toLong() == this) { "Forum topic ID is outside the supported integer range" }
        }

    /** Keep the existing public message-thread field compatible with TDLib's topic union. */
    private fun TdApi.MessageTopic?.toMessageThreadId(): Long? = when (this) {
        is TdApi.MessageTopicThread -> messageThreadId.takeIf { it != 0L }
        is TdApi.MessageTopicForum -> forumTopicId.toLong().takeIf { it != 0L }
        is TdApi.MessageTopicDirectMessages -> directMessagesChatTopicId.takeIf { it != 0L }
        is TdApi.MessageTopicSavedMessages -> savedMessagesTopicId.takeIf { it != 0L }
        else -> null
    }

    /** Compact data carrier for media parsing. */
    private data class MediaData(
        val mediaType: String,
        val fileName: String?,
        val mimeType: String?,
        val fileSize: Long?,
        val width: Int?,
        val height: Int?,
        val duration: Int?,
    )

    private fun extractFileFromMessage(message: TdApi.Message): TdApi.File? {
        return when (val content = message.content) {
            is TdApi.MessagePhoto -> content.photo?.sizes?.maxByOrNull { it.width * it.height }?.photo
            is TdApi.MessageVideo -> content.video?.video
            is TdApi.MessageDocument -> content.document?.document
            is TdApi.MessageAudio -> content.audio?.audio
            is TdApi.MessageVoiceNote -> content.voiceNote?.voice
            is TdApi.MessageSticker -> content.sticker?.sticker
            is TdApi.MessageAnimation -> content.animation?.animation
            is TdApi.MessageVideoNote -> content.videoNote?.video
            else -> null
        }
    }

    private fun extractFileName(message: TdApi.Message): String? {
        return when (val content = message.content) {
            is TdApi.MessageDocument -> content.document?.fileName
            is TdApi.MessageVideo -> content.video?.fileName
            is TdApi.MessageAudio -> content.audio?.fileName
            is TdApi.MessageAnimation -> content.animation?.fileName
            else -> null
        }
    }

    private fun extractMimeType(message: TdApi.Message): String? {
        return when (val content = message.content) {
            is TdApi.MessageDocument -> content.document?.mimeType
            is TdApi.MessageVideo -> content.video?.mimeType
            is TdApi.MessageAudio -> content.audio?.mimeType
            is TdApi.MessageVoiceNote -> content.voiceNote?.mimeType
            is TdApi.MessageAnimation -> content.animation?.mimeType
            else -> null
        }
    }

    private fun TdApi.ChatMember.toChatMember(): ChatMember {
        val userId = when (val memberId = this.memberId) {
            is TdApi.MessageSenderUser -> memberId.userId
            is TdApi.MessageSenderChat -> memberId.chatId
            else -> 0L
        }

        val user = try {
            if (userId > 0) getUserSafe(userId) else null
        } catch (_: Exception) {
            null
        }

        val status = when (this.status) {
            is TdApi.ChatMemberStatusCreator -> MemberStatus.CREATOR
            is TdApi.ChatMemberStatusAdministrator -> MemberStatus.ADMIN
            is TdApi.ChatMemberStatusMember -> MemberStatus.MEMBER
            is TdApi.ChatMemberStatusRestricted -> MemberStatus.RESTRICTED
            is TdApi.ChatMemberStatusLeft -> MemberStatus.LEFT
            is TdApi.ChatMemberStatusBanned -> MemberStatus.BANNED
            else -> MemberStatus.MEMBER
        }

        return ChatMember(
            userId = userId,
            firstName = user?.firstName ?: "",
            lastName = user?.lastName,
            username = user?.usernames?.activeUsernames?.firstOrNull(),
            status = status,
        )
    }
}

internal data class FailedMemberAdd(
    val userId: Long,
    val premiumWouldAllowInvite: Boolean,
    val premiumRequiredToSendMessages: Boolean,
)

private fun TdApi.FailedToAddMembers?.toFailedMemberAdds(): List<FailedMemberAdd> =
    this?.failedToAddMembers
        ?.map {
            FailedMemberAdd(
                userId = it.userId,
                premiumWouldAllowInvite = it.premiumWouldAllowInvite,
                premiumRequiredToSendMessages = it.premiumRequiredToSendMessages,
            )
        }
        .orEmpty()

internal fun formatFailedToAddMembers(
    operation: String,
    chatId: Long,
    failedMembers: List<FailedMemberAdd>,
): String {
    val members = failedMembers.joinToString(prefix = "[", postfix = "]") { member ->
        """{"user_id":${member.userId},"premium_would_allow_invite":${member.premiumWouldAllowInvite},"premium_required_to_send_messages":${member.premiumRequiredToSendMessages}}"""
    }
    return """Telegram rejected adding members during $operation: {"chat_id":$chatId,"failed_members":$members}"""
}
