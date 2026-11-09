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

/**
 * Shared TDLib helpers for the read, message, and admin adapters.
 * Request policy stays in [TdLibRequestExecutor]; this type only carries state those adapters share.
 */
internal class TdLibClientSupport(
    client: SimpleTelegramClient,
    rateLimiter: RateLimiter,
    circuitBreaker: CircuitBreaker,
    meterRegistry: MeterRegistry,
    internal val chatFolderState: ChatFolderState = ChatFolderState(),
    messageSendTracker: MessageSendTracker? = null,
    internal val floodGate: TelegramFloodGate = TelegramFloodGate(),
    requestSettings: TdLibProperties.Requests = TdLibProperties.Requests(),
    bulkhead: Bulkhead = TdLibClientService.accountBulkhead("telegram", requestSettings),
    timeoutHealth: TdLibTimeoutHealth = TdLibTimeoutHealth.alwaysCounting(),
) {
    internal val log = StructuredLogger.forClass<TdLibClientService>()
    private val requests = TdLibRequestExecutor(
        client, rateLimiter, circuitBreaker, meterRegistry, messageSendTracker,
        floodGate, requestSettings, bulkhead, timeoutHealth,
    )

    internal val DEFAULT_FORUM_TOPIC_ICON_COLOR = 0x6FB9F0
    internal val ALLOWED_FORUM_TOPIC_ICON_COLORS = setOf(
        0x6FB9F0, 0xFFD67E, 0xCB86DB, 0x8EEE98, 0xFF93B2, 0xFB6F5F,
    )
    internal val CREATE_CHAT_REFRESH_ATTEMPTS = 6
    internal val CREATE_CHAT_REFRESH_DELAY_MS = 250L
    internal val SPEECH_RECOGNITION_TIMEOUT_MS = 30_000L
    internal val SPEECH_RECOGNITION_POLL_INTERVAL_MS = 1_000L
    internal val SPEECH_RECOGNITION_DEADLINE_MARGIN_MS = 3_000L


    internal fun ReplyMarkupSpec.toTdLib(): TdApi.ReplyMarkup = when (type) {
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

    internal fun getSpeechRecognitionResult(chatId: Long, messageId: Long): TdApi.SpeechRecognitionResult? {
        val messages = withResilience("getMessages") {
            send<TdApi.Messages>(TdApi.GetMessages(chatId, longArrayOf(messageId)))
        }
        val message = messages.messages?.firstOrNull()
            ?: throw IllegalStateException("Message $messageId not found in chat $chatId")
        val voiceNote = (message.content as? TdApi.MessageVoiceNote)?.voiceNote
            ?: throw IllegalArgumentException("Message $messageId in chat $chatId is not a voice note")
        return voiceNote.speechRecognitionResult
    }

    internal fun TdApi.SpeechRecognitionResult?.toVoiceTranscriptionOrNull(
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

    internal fun TdApi.SpeechRecognitionResult?.partialTextOrNull(): String? =
        (this as? TdApi.SpeechRecognitionResultPending)?.partialText?.takeIf { it.isNotBlank() }


    internal fun forwardAndAwait(
        request: TdApi.ForwardMessages,
        toChatId: Long,
        messageIds: List<Long>,
    ): List<TdApi.Message> {
        val priorWriteDispatched = ToolCallDeadline.writeDispatched()
        val result = withResilience("forwardMessages") {
            send<TdApi.Messages>(request)
        }
        val pending = result.messages.orEmpty()
        val delivered = ArrayList<TdApi.Message>(pending.size)
        val deliveredSourceIds = ArrayList<Long>(pending.size)
        val failures = ArrayList<Exception>()
        // TDLib returns null for messages it cannot forward at all (for example
        // protected content or deleted messages); a retry will not help them.
        val notForwardable = messageIds.filterIndexed { index, _ -> pending.getOrNull(index) == null }
        pending.forEachIndexed { index, message ->
            if (message == null) return@forEachIndexed
            // Every item is already dispatched. After a failure, inspect the
            // remaining known outcomes without waiting again: a later item can
            // already have succeeded, even if the first one failed.
            val final = try {
                awaitFinalMessage(message, request, priorWriteDispatched, pollOnly = failures.isNotEmpty())
            } catch (error: TelegramApiException) {
                failures += error
                return@forEachIndexed
            } catch (error: SendOutcomeUnknownException) {
                failures += error
                return@forEachIndexed
            } catch (error: TelegramTimeoutException) {
                failures += error
                return@forEachIndexed
            }
            delivered += final
            messageIds.getOrNull(index)?.let(deliveredSourceIds::add)
        }
        if (failures.isNotEmpty()) {
            if (delivered.isEmpty() && notForwardable.isEmpty()) {
                if (failures.all { it is TelegramApiException && it.isFloodWait }) {
                    // Only every item's definite rejection proves the batch safe to repeat.
                    throw failures.filterIsInstance<TelegramApiException>().maxBy { it.retryAfterSeconds ?: 0 }
                }
                if (pending.count { it != null } == 1 || failures.all { it is SendOutcomeUnknownException }) {
                    throw failures.first()
                }
            }
            throw ForwardPartiallyDeliveredException(
                toChatId, deliveredSourceIds.toList(),
                messageIds.filterNot { it in deliveredSourceIds || it in notForwardable },
                floodGate.remainingSeconds(TelegramFloodGate.scopeOf(request)), failures.first(),
                notForwardableMessageIds = notForwardable,
            )
        }
        return delivered
    }

    internal fun reactionTypeLabel(type: TdApi.ReactionType?): String = when (type) {
        is TdApi.ReactionTypeEmoji -> type.emoji ?: ""
        is TdApi.ReactionTypeCustomEmoji -> "custom:${type.customEmojiId}"
        is TdApi.ReactionTypePaid -> "paid"
        else -> type?.javaClass?.simpleName ?: "unknown"
    }
    internal fun setForumTopicClosed(chatId: Long, messageThreadId: Long, isClosed: Boolean): Boolean {
        log.info("Setting forum topic {} in chat {} closed={}", messageThreadId, chatId, isClosed)

        withResilience("toggleForumTopicIsClosed") {
            send<TdApi.Ok>(TdApi.ToggleForumTopicIsClosed(chatId, messageThreadId.toForumTopicId(), isClosed))
        }
        return true
    }

    internal fun pollChatUntilSettled(
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

    internal fun TdApi.ChatInviteLink.toInviteLinkInfo(chatId: Long): ChatInviteLinkInfo = ChatInviteLinkInfo(
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


    internal fun albumAndAwait(request: TdApi.SendMessageAlbum, chatId: Long): List<TdApi.Message> {
        val priorWriteDispatched = ToolCallDeadline.writeDispatched()
        val pending = withResilience("sendMessageAlbum") { send<TdApi.Messages>(request) }.messages.orEmpty().filterNotNull()
        val delivered = ArrayList<TdApi.Message>(pending.size)
        val failures = ArrayList<Exception>()
        pending.forEach { message ->
            // All items are dispatched together; after a failure, only poll the rest.
            try {
                delivered += awaitFinalMessage(message, request, priorWriteDispatched, pollOnly = failures.isNotEmpty())
            } catch (error: TelegramApiException) {
                failures += error
            } catch (error: SendOutcomeUnknownException) {
                failures += error
            } catch (error: TelegramTimeoutException) {
                failures += error
            }
        }
        if (failures.isEmpty() && delivered.size == request.inputMessageContents.size) return delivered
        // Only a definite rejection of every item proves the album safe to repeat.
        if (delivered.isEmpty() && failures.isNotEmpty() && failures.all { it is TelegramApiException }) throw failures.first()
        throw SendOutcomeUnknownException(
            chatId, delivered.firstOrNull()?.id,
            failures.firstOrNull() ?: IllegalStateException(
                "Telegram accepted ${delivered.size} of ${request.inputMessageContents.size} album items",
            ),
        )
    }

    internal fun sendContent(chatId: Long, content: TdApi.InputMessageContent): TelegramMessage {
        val request = TdApi.SendMessage().apply {
            this.chatId = chatId
            this.inputMessageContent = content
        }
        return sendAndAwaitFinal("sendMessage", request).toTelegramMessage(getChatTitleSafe(chatId))
    }

    // ── Internal helpers ────────────────────────────────────────────────────

    internal fun tdPrivacySetting(setting: PrivacySetting): TdApi.UserPrivacySetting = when (setting) {
        PrivacySetting.SHOW_STATUS -> TdApi.UserPrivacySettingShowStatus()
        PrivacySetting.SHOW_PROFILE_PHOTO -> TdApi.UserPrivacySettingShowProfilePhoto()
        PrivacySetting.SHOW_PHONE_NUMBER -> TdApi.UserPrivacySettingShowPhoneNumber()
        PrivacySetting.SHOW_LINK_IN_FORWARDED_MESSAGES -> TdApi.UserPrivacySettingShowLinkInForwardedMessages()
        PrivacySetting.ALLOW_CHAT_INVITES -> TdApi.UserPrivacySettingAllowChatInvites()
        PrivacySetting.ALLOW_CALLS -> TdApi.UserPrivacySettingAllowCalls()
        PrivacySetting.ALLOW_PEER_TO_PEER_CALLS -> TdApi.UserPrivacySettingAllowPeerToPeerCalls()
        PrivacySetting.ALLOW_PRIVATE_VOICE_AND_VIDEO_NOTES -> TdApi.UserPrivacySettingAllowPrivateVoiceAndVideoNoteMessages()
    }

    internal fun privacyRuleFromTd(rule: TdApi.UserPrivacySettingRule): PrivacyRule = when (rule) {
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

    internal fun privacyRuleToTd(rule: PrivacyRule): TdApi.UserPrivacySettingRule = when (rule.kind) {
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

    internal fun tdBotCommandScope(scope: BotCommandScope): TdApi.BotCommandScope = when (scope.kind) {
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

    internal fun chatPermissionsFromTd(permissions: TdApi.ChatPermissions?): ChatPermissions {
        val value = permissions ?: return ChatPermissions()
        return ChatPermissions(
            value.canSendBasicMessages, value.canSendAudios, value.canSendDocuments, value.canSendPhotos,
            value.canSendVideos, value.canSendVideoNotes, value.canSendVoiceNotes, value.canSendPolls,
            value.canSendOtherMessages, value.canAddLinkPreviews, value.canReactToMessages, value.canEditTag,
            value.canChangeInfo, value.canInviteUsers, value.canPinMessages, value.canCreateTopics,
        )
    }

    internal fun chatPermissionsToTd(permissions: ChatPermissions): TdApi.ChatPermissions = TdApi.ChatPermissions(
        permissions.canSendBasicMessages, permissions.canSendAudios, permissions.canSendDocuments, permissions.canSendPhotos,
        permissions.canSendVideos, permissions.canSendVideoNotes, permissions.canSendVoiceNotes, permissions.canSendPolls,
        permissions.canSendOtherMessages, permissions.canAddLinkPreviews, permissions.canReactToMessages, permissions.canEditTag,
        permissions.canChangeInfo, permissions.canInviteUsers, permissions.canPinMessages, permissions.canCreateTopics,
    )

    internal fun chatAdministratorRightsToTd(rights: ChatAdministratorRights): TdApi.ChatAdministratorRights =
        TdLibRequestBuilders.administratorRights(rights)

    // Every capability shares one request policy and its per-thread nesting state.
    internal fun <T : TdApi.Object> send(function: TdApi.Function<*>): T = requests.send(function)

    internal fun <T> withResilience(operation: String, block: () -> T): T =
        requests.withResilience(operation, block)

    internal fun <T> holdingBulkhead(operation: String, block: () -> T): T =
        requests.holdingBulkhead(operation, block)

    internal fun sendAndAwaitFinal(operation: String, request: TdApi.Function<*>): TdApi.Message =
        requests.sendAndAwaitFinal(operation, request)

    internal fun awaitFinalMessage(
        message: TdApi.Message, request: TdApi.Function<*>, priorWriteDispatched: Boolean, pollOnly: Boolean = false,
    ): TdApi.Message = requests.awaitFinalMessage(message, request, priorWriteDispatched, pollOnly)

    internal fun <T : TdApi.Object> lookupBestEffort(lookup: String, function: TdApi.Function<*>): T? =
        requests.lookupBestEffort(lookup, function)

    internal fun formatText(text: String, parseMode: ParseMode): TdApi.FormattedText {
        val tdParseMode = TextFormatter.toTdLibParseMode(parseMode)
        if (tdParseMode == null) {
            return TextFormatter.plainText(text)
        }
        // Parse before dispatching the write. A rejected format must be visible
        // to the caller, and a timeout or flood wait must retain its retry policy.
        // Silently sending plain text here can publish unintended raw markup.
        return try {
            send(TdApi.ParseTextEntities(text, tdParseMode))
        } catch (error: TelegramApiException) {
            if (error.kind == dev.telegrammcp.server.exception.TelegramErrorKind.INVALID_INPUT) {
                error.preWriteValidationFailed = !error.priorWriteDispatched
            }
            throw error
        }
    }

    internal fun getChatTitleSafe(chatId: Long): String? =
        lookupBestEffort<TdApi.Chat>("getChat", TdApi.GetChat(chatId))?.title

    internal fun getUserSafe(userId: Long): TdApi.User? =
        lookupBestEffort("getUser", TdApi.GetUser(userId))

    // ── Mappers: TDLib → domain models ──────────────────────────────────────

    internal fun TdApi.Message.toTelegramMessage(chatTitle: String? = null): TelegramMessage {
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

        return TdLibMessageMapper.map(this, chatTitle, senderName)
    }

    internal fun TdApi.Message.toScheduledMessage(chatTitle: String?): ScheduledMessage {
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

    internal fun TdApi.ChatFolderInfo.toChatFolderInfo(): ChatFolderInfo = ChatFolderInfo(
        id = id,
        title = name?.text?.text.orEmpty(),
        iconName = icon?.name?.takeIf { it.isNotBlank() },
        colorId = colorId,
        isShareable = isShareable,
        hasMyInviteLinks = hasMyInviteLinks,
    )

    internal fun TdApi.ChatFolder.toChatFolderDefinition(): ChatFolderDefinition = ChatFolderDefinition(
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

    internal fun ChatFolderDefinition.toTdLib(): TdApi.ChatFolder = TdApi.ChatFolder(
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

    internal fun TdApi.Chat.toChatInfo(fullInfo: TdApi.Object? = null): ChatInfo {
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

    internal fun TdApi.ForumTopicInfo.toForumTopicInfoModel(isPinned: Boolean, unreadCount: Int): ForumTopicInfoModel =
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

    internal fun TdApi.User.toUserInfo(): UserInfo {
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

    internal fun TdApi.Message.toMediaInfo(chatId: Long): MediaInfo {
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
    internal fun forumThreadTopic(messageThreadId: Long): TdApi.MessageTopic =
        TdApi.MessageTopicForum(messageThreadId.toForumTopicId())

    /** Forum-management endpoints now use a bounded integer topic ID. */
    internal fun Long.toForumTopicId(): Int =
        toInt().also { topicId ->
            require(topicId.toLong() == this) { "Forum topic ID is outside the supported integer range" }
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

    internal fun extractFileFromMessage(message: TdApi.Message): TdApi.File? {
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

    internal fun extractFileName(message: TdApi.Message): String? {
        return when (val content = message.content) {
            is TdApi.MessageDocument -> content.document?.fileName
            is TdApi.MessageVideo -> content.video?.fileName
            is TdApi.MessageAudio -> content.audio?.fileName
            is TdApi.MessageAnimation -> content.animation?.fileName
            else -> null
        }
    }

    internal fun extractMimeType(message: TdApi.Message): String? {
        return when (val content = message.content) {
            is TdApi.MessageDocument -> content.document?.mimeType
            is TdApi.MessageVideo -> content.video?.mimeType
            is TdApi.MessageAudio -> content.audio?.mimeType
            is TdApi.MessageVoiceNote -> content.voiceNote?.mimeType
            is TdApi.MessageAnimation -> content.animation?.mimeType
            else -> null
        }
    }

    internal fun TdApi.ChatMember.toChatMember(): ChatMember {
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
            administratorRights = (this.status as? TdApi.ChatMemberStatusAdministrator)?.rights
                ?.let(TdLibRequestBuilders::administratorRights),
        )
    }

}
