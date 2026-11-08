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

/** Read and lookup calls. They do not send messages or change chat membership. */
internal class TdLibReadAdapter(
    private val support: TdLibClientSupport,
) {
    // ── Messages ────────────────────────────────────────────────────────────
    fun getHistory(chatId: Long, fromMessageId: Long, offset: Int, limit: Int): List<TelegramMessage> = with(support) {
                log.info("Getting history for chat {} (from={}, offset={}, limit={})", chatId, fromMessageId, offset, limit)

                val messages = withResilience("getChatHistory") {
                    send<TdApi.Messages>(TdApi.GetChatHistory(chatId, fromMessageId, offset, limit, false))
                }

                val chatTitle = getChatTitleSafe(chatId)
                return messages.messages?.map { it.toTelegramMessage(chatTitle) } ?: emptyList()
            
    }


    fun searchMessages(chatId: Long, query: String, offset: Long, limit: Int): List<TelegramMessage> = with(support) {
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


    // ── Chats ───────────────────────────────────────────────────────────────
    fun getChats(limit: Int): List<ChatInfo> = with(support) {
                log.info("Loading chats (limit={})", limit)

                // First load chats into TDLib's internal cache
                withResilience("loadChats") {
                    try {
                        send<TdApi.Ok>(TdApi.LoadChats(TdApi.ChatListMain(), limit))
                    } catch (error: TelegramApiException) {
                        // Only 404 means all chats are loaded. Other errors must not
                        // turn an incomplete cache into an apparently successful list.
                        if (error.errorCode != 404) throw error
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


    fun getChat(chatId: Long): ChatInfo = with(support) {
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


    fun listChatFolders(): ChatFolderListing = with(support) {
        chatFolderState.snapshot()
    }


    fun getChatFolder(folderId: Int): ChatFolderDetails = with(support) {
                require(folderId > 0) { "chat folder ID must be positive" }
                log.info("Getting chat folder {}", folderId)
                val folder = withResilience("getChatFolder") {
                    send<TdApi.ChatFolder>(TdApi.GetChatFolder(folderId))
                }
                return ChatFolderDetails(folderId, folder.toChatFolderDefinition())
            
    }


    fun getChatMembers(chatId: Long, query: String, offset: Int, limit: Int): List<ChatMember> = with(support) {
                log.info("Getting members of chat {} (query='{}', offset={}, limit={})", chatId, query, offset, limit)

                val members = withResilience("searchChatMembers") {
                    send<TdApi.ChatMembers>(TdApi.SearchChatMembers(chatId, query, limit, null))
                }

                return members.members?.map { it.toChatMember() } ?: emptyList()
            
    }


    // ── Users & Contacts ────────────────────────────────────────────────────
    fun getMe(): UserInfo = with(support) {
                log.info("Getting current user profile")

                val me = withResilience("getMe") { send<TdApi.User>(TdApi.GetMe()) }
                return me.toUserInfo()
            
    }


    fun getContacts(): List<ContactInfo> = with(support) {
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


    fun getUser(userId: Long): UserInfo = with(support) {
                log.info("Getting user info for {}", userId)

                val user = withResilience("getUser") { send<TdApi.User>(TdApi.GetUser(userId)) }
                return user.toUserInfo()
            
    }


    // ── Media ───────────────────────────────────────────────────────────────
    fun getMediaInfo(chatId: Long, messageId: Long): MediaInfo = with(support) {
                log.info("Getting media info for message {} in chat {}", messageId, chatId)

                val messages = withResilience("getMessages") {
                    send<TdApi.Messages>(TdApi.GetMessages(chatId, longArrayOf(messageId)))
                }
                val message = messages.messages?.firstOrNull()
                    ?: throw IllegalStateException("Message $messageId not found in chat $chatId")

                return message.toMediaInfo(chatId)
            
    }


    fun downloadMedia(chatId: Long, messageId: Long): DownloadResult = with(support) {
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


    fun transcribeVoiceNote(chatId: Long, messageId: Long): VoiceTranscription = with(support) {
                log.info("Requesting native speech recognition for message {} in chat {}", messageId, chatId)

                val initial = getSpeechRecognitionResult(chatId, messageId)
                initial.toVoiceTranscriptionOrNull(chatId, messageId)?.let { return it }

                withResilience("recognizeSpeech") {
                    send<TdApi.Ok>(TdApi.RecognizeSpeech(chatId, messageId))
                }

                // Stop polling early enough to answer PENDING inside the tool call's deadline.
                val pollBudget = minOf(
                    TimeUnit.MILLISECONDS.toNanos(SPEECH_RECOGNITION_TIMEOUT_MS),
                    ToolCallDeadline.remainingNanos()?.let { it - TimeUnit.MILLISECONDS.toNanos(SPEECH_RECOGNITION_DEADLINE_MARGIN_MS) }
                        ?: Long.MAX_VALUE,
                )
                val deadline = System.nanoTime() + pollBudget
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


    fun getPinnedMessages(chatId: Long): List<TelegramMessage> = with(support) {
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


    fun viewMessages(chatId: Long, messageIds: List<Long>): Boolean = with(support) {
                log.info("Marking {} messages as read in chat {}", messageIds.size, chatId)

                val request = TdApi.ViewMessages().apply {
                    this.chatId = chatId
                    this.messageIds = messageIds.toLongArray()
                    this.forceRead = true
                }

                withResilience("viewMessages") { send<TdApi.Ok>(request) }
                return true
            
    }


    fun searchGlobal(query: String, limit: Int): List<TelegramMessage> = with(support) {
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


    fun getScheduledMessages(chatId: Long): List<ScheduledMessage> = with(support) {
                log.info("Getting scheduled messages for chat {}", chatId)
                val messages = withResilience("getChatScheduledMessages") {
                    send<TdApi.Messages>(TdApi.GetChatScheduledMessages(chatId))
                }
                val chatTitle = getChatTitleSafe(chatId)
                return messages.messages?.map { it.toScheduledMessage(chatTitle) } ?: emptyList()
            
    }


    fun getPrivacySettingRules(setting: PrivacySetting): PrivacySettingRules = with(support) {
                log.info("Getting privacy setting rules for {}", setting)
                val result = withResilience("getUserPrivacySettingRules") {
                    send<TdApi.UserPrivacySettingRules>(TdApi.GetUserPrivacySettingRules(tdPrivacySetting(setting)))
                }
                return PrivacySettingRules(setting, result.rules.map(::privacyRuleFromTd))
            
    }


    fun getBotCommands(scope: BotCommandScope, languageCode: String): List<BotCommand> = with(support) {
                log.info("Getting bot commands for scope {}", scope.kind)
                val result = withResilience("getCommands") {
                    send<TdApi.BotCommands>(TdApi.GetCommands(tdBotCommandScope(scope), languageCode))
                }
                return result.commands.map { BotCommand(it.command, it.description) }
            
    }


    fun getChatPermissions(chatId: Long): ChatPermissions = with(support) {
                log.info("Getting default permissions for chat {}", chatId)
                val chat = withResilience("getChat") { send<TdApi.Chat>(TdApi.GetChat(chatId)) }
                return chatPermissionsFromTd(chat.permissions)
            
    }


    // ── Entity resolution ───────────────────────────────────────────────────
    fun resolveUsername(username: String): Long = with(support) {
                log.info("Resolving username: @{}", username)

                val chat = withResilience("searchPublicChat") {
                    send<TdApi.Chat>(TdApi.SearchPublicChat(username))
                }
                return chat.id
            
    }


    fun resolvePhone(phoneNumber: String): Long = with(support) {
                log.info("Resolving phone number")

                val user = withResilience("searchUserByPhoneNumber") {
                    send<TdApi.User>(TdApi.SearchUserByPhoneNumber(phoneNumber, false))
                }
                return user.id
            
    }


    fun resolveSelfChat(): Long = with(support) {
                log.info("Resolving self chat")

                val me = withResilience("getMe") {
                    send<TdApi.User>(TdApi.GetMe())
                }
                val chat = withResilience("createPrivateChat") {
                    send<TdApi.Chat>(TdApi.CreatePrivateChat(me.id, true))
                }
                return chat.id
            
    }


    fun getMessageReactions(chatId: Long, messageId: Long, limit: Int): MessageReactionSummary = with(support) {
                log.info("Fetching reactions for message {} in chat {} (limit={})", messageId, chatId, limit)

                val message = withResilience("getMessage") {
                    send<TdApi.Message>(TdApi.GetMessage(chatId, messageId))
                }
                val messageReactions = message.interactionInfo?.reactions
                val reactionCounts = messageReactions?.reactions?.map { reaction ->
                    ReactionCount(
                        emoji = reactionTypeLabel(reaction.type),
                        totalCount = reaction.totalCount,
                        isChosen = reaction.isChosen,
                    )
                } ?: emptyList()

                fun summary(
                    reactions: List<ReactionInfo>,
                    canGetAddedReactions: Boolean,
                ) = MessageReactionSummary(
                    chatId = chatId,
                    messageId = messageId,
                    reactions = reactions,
                    reactionCounts = reactionCounts,
                    canGetAddedReactions = canGetAddedReactions,
                )

                // Broadcast channels expose aggregate counters on the message but not
                // the list of users behind them. TDLib advertises this explicitly.
                if (messageReactions?.canGetAddedReactions == false) {
                    return summary(emptyList(), canGetAddedReactions = false)
                }

                val request = TdApi.GetMessageAddedReactions().apply {
                    this.chatId = chatId
                    this.messageId = messageId
                    this.reactionType = null
                    this.offset = ""
                    this.limit = limit.coerceIn(1, 100)
                }
                val result = try {
                    withResilience("getMessageAddedReactions") {
                        send<TdApi.AddedReactions>(request)
                    }
                } catch (error: TelegramApiException) {
                    // Protect against stale/missing canGetAddedReactions metadata. The
                    // aggregate counters above are still valid for broadcast posts.
                    if (error.errorCode == 403 && error.errorMessage == "BROADCAST_FORBIDDEN") {
                        log.info(
                            "Telegram hides reaction senders for broadcast message {} in chat {}; returning counts only",
                            messageId,
                            chatId,
                        )
                        return summary(emptyList(), canGetAddedReactions = false)
                    }
                    throw error
                }

                val reactions = result.reactions?.map { r ->
                    val emoji = reactionTypeLabel(r.type)
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

                return summary(reactions, canGetAddedReactions = true)
            
    }


    fun getMessageViewers(chatId: Long, messageId: Long): List<MessageViewerInfo> = with(support) {
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


    fun getMessageContext(chatId: Long, messageId: Long, contextSize: Int): List<TelegramMessage> = with(support) {
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
    fun listInlineButtons(chatId: Long, messageId: Long): List<InlineButtonInfo> = with(support) {
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


    fun listForumTopics(chatId: Long, query: String, limit: Int): List<ForumTopicInfoModel> = with(support) {
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


    fun getChatInviteLinks(chatId: Long, includeRevoked: Boolean, limit: Int): List<ChatInviteLinkInfo> = with(support) {
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


    fun getChatAdmins(chatId: Long, limit: Int): List<ChatMember> = with(support) {
                log.info("Listing admins of chat {}", chatId)

                val members = withResilience("searchChatMembers") {
                    send<TdApi.ChatMembers>(
                        TdApi.SearchChatMembers(chatId, "", limit, TdApi.ChatMembersFilterAdministrators()),
                    )
                }
                return members.members?.map { it.toChatMember() } ?: emptyList()
            
    }


    fun getBannedChatMembers(chatId: Long, limit: Int): List<ChatMember> = with(support) {
                log.info("Listing banned users of chat {}", chatId)

                val members = withResilience("searchChatMembers") {
                    send<TdApi.ChatMembers>(
                        TdApi.SearchChatMembers(chatId, "", limit, TdApi.ChatMembersFilterBanned()),
                    )
                }
                return members.members?.map { it.toChatMember() } ?: emptyList()
            
    }


    fun getInstalledStickerSets(limit: Int): List<StickerSetInfo> = with(support) {
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


    fun getDrafts(): List<DraftInfo> = with(support) {
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


    fun getUserProfilePhotos(userId: Long, limit: Int): List<UserPhotoInfo> = with(support) {
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
    fun searchPublicChats(query: String, limit: Int): List<ChatInfo> = with(support) {
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


    fun searchContactsByQuery(query: String, limit: Int): List<ContactInfo> = with(support) {
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


    fun getBlockedUsers(limit: Int): List<UserInfo> = with(support) {
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


    fun getUserStatus(userId: Long): String = with(support) {
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


    fun getChatEventLog(chatId: Long, limit: Int): List<AdminActionInfo> = with(support) {
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
    fun getMessageByLink(link: String): TelegramMessage = with(support) {
                log.info("Resolving message link")

                val info = withResilience("getMessageLinkInfo") {
                    send<TdApi.MessageLinkInfo>(TdApi.GetMessageLinkInfo(link))
                }
                val message = info.message
                    ?: throw IllegalStateException("Link does not resolve to a specific message")
                val chatTitle = getChatTitleSafe(info.chatId)
                return message.toTelegramMessage(chatTitle)
            
    }


    fun getMessageLink(
        chatId: Long,
        messageId: Long,
        forAlbum: Boolean,
        inMessageThread: Boolean,
    ): TelegramMessageLink = with(support) {
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


    fun getLastInteractionWithContact(contactId: Long): TelegramMessage? = with(support) {
                log.info("Fetching last interaction with contact {}", contactId)

                val chat = withResilience("createPrivateChat") {
                    send<TdApi.Chat>(TdApi.CreatePrivateChat(contactId, true))
                }
                val last = chat.lastMessage ?: return null
                return last.toTelegramMessage(chat.title)
            
    }


    fun getGroupsInCommon(userId: Long, limit: Int): List<ChatInfo> = with(support) {
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


    fun getChatJoinRequests(chatId: Long, query: String, inviteLink: String, limit: Int): ChatJoinRequestList = with(support) {
                log.info("Listing join requests of chat {} (limit={})", chatId, limit)
                val result = withResilience("getChatJoinRequests") {
                    send<TdApi.ChatJoinRequests>(TdApi.GetChatJoinRequests(chatId, inviteLink, query, null, limit.coerceIn(1, 100)))
                }
                return ChatJoinRequestList(
                    chatId = chatId,
                    totalCount = result.totalCount,
                    requests = result.requests.orEmpty().map {
                        ChatJoinRequestInfo(it.userId, Instant.ofEpochSecond(it.date.toLong()), it.bio.orEmpty())
                    },
                )
            
    }


    fun getChatStatistics(chatId: Long): ChatStatisticsSummary = with(support) {
                log.info("Loading statistics of chat {}", chatId)
                val statistics = withResilience("getChatStatistics") {
                    send<TdApi.ChatStatistics>(TdApi.GetChatStatistics(chatId, false))
                }
                return TdLibRequestBuilders.chatStatistics(chatId, statistics)
            
    }
}
