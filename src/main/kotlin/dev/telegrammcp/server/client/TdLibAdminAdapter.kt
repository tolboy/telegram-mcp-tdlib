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

/** Chat administration, membership, profile, and folder changes. */
internal class TdLibAdminAdapter(
    private val support: TdLibClientSupport,
    private val reads: TdLibReadAdapter,
) {

    fun createChatFolder(folder: ChatFolderDefinition): ChatFolderInfo = with(support) {
                log.info("Creating chat folder '{}'", folder.title)
                return withResilience("createChatFolder") {
                    send<TdApi.ChatFolderInfo>(TdApi.CreateChatFolder(folder.toTdLib()))
                }.toChatFolderInfo()

    }


    fun updateChatFolder(folderId: Int, folder: ChatFolderDefinition): ChatFolderInfo = with(support) {
                require(folderId > 0) { "chat folder ID must be positive" }
                log.info("Updating chat folder {}", folderId)
                return withResilience("editChatFolder") {
                    send<TdApi.ChatFolderInfo>(TdApi.EditChatFolder(folderId, folder.toTdLib()))
                }.toChatFolderInfo()

    }


    fun deleteChatFolder(folderId: Int): Boolean = with(support) {
                require(folderId > 0) { "chat folder ID must be positive" }
                log.info("Deleting chat folder {} without leaving chats", folderId)
                withResilience("deleteChatFolder") {
                    send<TdApi.Ok>(TdApi.DeleteChatFolder(folderId, longArrayOf()))
                }
                return true

    }


    fun reorderChatFolders(folderIds: List<Int>, mainChatListPosition: Int): Boolean = with(support) {
                require(folderIds.isNotEmpty()) { "folder ID list must not be empty" }
                require(folderIds.all { it > 0 }) { "chat folder IDs must be positive" }
                log.info("Reordering {} chat folders (main list position {})", folderIds.size, mainChatListPosition)

                withResilience("reorderChatFolders") {
                    send<TdApi.Ok>(TdApi.ReorderChatFolders(folderIds.toIntArray(), mainChatListPosition))
                }
                return true

    }


    // ── Chat management ────────────────────────────────────────────────────
    fun createBasicGroup(title: String, userIds: List<Long>): ChatInfo = with(support) {
                log.info("Creating basic group '{}' with {} members", title, userIds.size)

                val result = withResilience("createNewBasicGroupChat") {
                    send<TdApi.CreatedBasicGroupChat>(TdApi.CreateNewBasicGroupChat(userIds.toLongArray(), title, 0))
                }
                val failedMembers = result.failedToAddMembers.toFailedMemberAdds()
                if (failedMembers.isNotEmpty()) {
                    throw IllegalStateException(formatFailedToAddMembers("create_basic_group", result.chatId, failedMembers))
                }

                return reads.getChat(result.chatId)

    }


    fun addChatMembers(chatId: Long, userIds: List<Long>): Boolean = with(support) {
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


    fun leaveChat(chatId: Long): Boolean = with(support) {
                log.info("Leaving chat {}", chatId)

                withResilience("leaveChat") { send<TdApi.Ok>(TdApi.LeaveChat(chatId)) }
                return true

    }


    fun banChatMember(chatId: Long, userId: Long): Boolean = with(support) {
                log.info("Banning user {} from chat {}", userId, chatId)

                val sender = TdApi.MessageSenderUser(userId)
                withResilience("banChatMember") {
                    send<TdApi.Ok>(TdApi.BanChatMember(chatId, sender, 0, false))
                }
                return true

    }


    fun unbanChatMember(chatId: Long, userId: Long): Boolean = with(support) {
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


    fun setChatMemberAdmin(chatId: Long, userId: Long, isAdmin: Boolean): Boolean = with(support) {
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


    fun setPrivacySettingRules(rules: PrivacySettingRules): Boolean = with(support) {
                log.info("Setting privacy rules for {}", rules.setting)
                val tdRules = TdApi.UserPrivacySettingRules(rules.rules.map(::privacyRuleToTd).toTypedArray())
                withResilience("setUserPrivacySettingRules") {
                    send<TdApi.Ok>(TdApi.SetUserPrivacySettingRules(tdPrivacySetting(rules.setting), tdRules))
                }
                return true

    }


    fun setBotCommands(scope: BotCommandScope, languageCode: String, commands: List<BotCommand>): Boolean = with(support) {
                log.info("Setting {} bot commands for scope {}", commands.size, scope.kind)
                val tdCommands = commands.map(TdLibRequestBuilders::botCommand).toTypedArray()
                withResilience("setCommands") {
                    send<TdApi.Ok>(TdApi.SetCommands(tdBotCommandScope(scope), languageCode, tdCommands))
                }
                return true

    }


    fun setChatPermissions(chatId: Long, permissions: ChatPermissions): Boolean = with(support) {
                log.info("Setting default permissions for chat {}", chatId)
                withResilience("setChatPermissions") {
                    send<TdApi.Ok>(TdApi.SetChatPermissions(chatId, chatPermissionsToTd(permissions)))
                }
                return true

    }


    fun setChatMemberPermissions(
        chatId: Long,
        userId: Long,
        permissions: ChatPermissions,
        restrictedUntilDate: Int,
        isMember: Boolean,
    ): Boolean = with(support) {
                log.info("Setting member permissions for user {} in chat {}", userId, chatId)
                val status = TdApi.ChatMemberStatusRestricted(isMember, restrictedUntilDate, chatPermissionsToTd(permissions))
                withResilience("setChatMemberStatus") {
                    send<TdApi.Ok>(TdApi.SetChatMemberStatus(chatId, TdApi.MessageSenderUser(userId), status))
                }
                return true

    }


    fun setChatMemberAdministratorRights(chatId: Long, userId: Long, rights: ChatAdministratorRights): Boolean = with(support) {
                log.info("Setting administrator rights for user {} in chat {}", userId, chatId)
                val status = TdApi.ChatMemberStatusAdministrator(false, chatAdministratorRightsToTd(rights))
                withResilience("setChatMemberStatus") {
                    send<TdApi.Ok>(TdApi.SetChatMemberStatus(chatId, TdApi.MessageSenderUser(userId), status))
                }
                return true

    }


    fun setChatTitle(chatId: Long, title: String): Boolean = with(support) {
                log.info("Setting title of chat {} to '{}'", chatId, title)

                withResilience("setChatTitle") {
                    send<TdApi.Ok>(TdApi.SetChatTitle(chatId, title))
                }
                return true

    }


    fun setChatDescription(chatId: Long, description: String): Boolean = with(support) {
                log.info("Setting description of chat {} ({} chars)", chatId, description.length)

                withResilience("setChatDescription") {
                    send<TdApi.Ok>(TdApi.SetChatDescription(chatId, description))
                }
                return true

    }


    fun setChatSlowModeDelay(chatId: Long, delaySeconds: Int): Boolean = with(support) {
                log.info("Setting slow-mode delay of chat {} to {}s", chatId, delaySeconds)

                withResilience("setChatSlowModeDelay") {
                    send<TdApi.Ok>(TdApi.SetChatSlowModeDelay(chatId, delaySeconds))
                }
                return true

    }


    fun archiveChat(chatId: Long): Boolean = with(support) {
                log.info("Archiving chat {}", chatId)

                withResilience("addChatToList") {
                    send<TdApi.Ok>(TdApi.AddChatToList(chatId, TdApi.ChatListArchive()))
                }
                return true

    }


    fun unarchiveChat(chatId: Long): Boolean = with(support) {
                log.info("Unarchiving chat {}", chatId)

                withResilience("addChatToList") {
                    send<TdApi.Ok>(TdApi.AddChatToList(chatId, TdApi.ChatListMain()))
                }
                return true

    }


    fun muteChat(chatId: Long, muteFor: Int): Boolean = with(support) {
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


    fun unmuteChat(chatId: Long): Boolean = with(support) {
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
    fun addContact(userId: Long, firstName: String, lastName: String?, phoneNumber: String?): Boolean = with(support) {
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


    fun removeContacts(userIds: List<Long>): Boolean = with(support) {
                log.info("Removing {} contacts", userIds.size)

                withResilience("removeContacts") {
                    send<TdApi.Ok>(TdApi.RemoveContacts(userIds.toLongArray()))
                }
                return true

    }


    fun blockUser(userId: Long): Boolean = with(support) {
                log.info("Blocking user {}", userId)

                val sender = TdApi.MessageSenderUser(userId)
                withResilience("setMessageSenderBlockList") {
                    send<TdApi.Ok>(TdApi.SetMessageSenderBlockList(sender, TdApi.BlockListMain()))
                }
                return true

    }


    fun unblockUser(userId: Long): Boolean = with(support) {
                log.info("Unblocking user {}", userId)

                val sender = TdApi.MessageSenderUser(userId)
                withResilience("setMessageSenderBlockList") {
                    send<TdApi.Ok>(TdApi.SetMessageSenderBlockList(sender, null))
                }
                return true

    }


    fun createForumTopic(
        chatId: Long,
        name: String,
        iconColor: Int?,
        customEmojiId: Long?,
    ): ForumTopicInfoModel = with(support) {
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


    fun editForumTopic(
        chatId: Long,
        messageThreadId: Long,
        name: String?,
        editIconCustomEmoji: Boolean,
        customEmojiId: Long?,
    ): Boolean = with(support) {
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


    fun closeForumTopic(chatId: Long, messageThreadId: Long): Boolean = with(support) {
        setForumTopicClosed(chatId, messageThreadId, isClosed = true)
    }


    fun reopenForumTopic(chatId: Long, messageThreadId: Long): Boolean = with(support) {
        setForumTopicClosed(chatId, messageThreadId, isClosed = false)
    }


    fun setForumTopicsEnabled(chatId: Long, enabled: Boolean, hasForumTabs: Boolean): Boolean = with(support) {
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
    fun createSupergroupOrChannel(
        title: String,
        description: String,
        isSupergroup: Boolean,
        isForum: Boolean,
    ): ChatInfo = with(support) {
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


    fun createInviteLink(chatId: Long): ChatInviteLinkInfo = with(support) {
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


    fun revokeChatInviteLink(chatId: Long, inviteLink: String): List<ChatInviteLinkInfo> = with(support) {
                log.info("Revoking an invite link for chat {}", chatId)

                val links = withResilience("revokeChatInviteLink") {
                    send<TdApi.ChatInviteLinks>(TdApi.RevokeChatInviteLink(chatId, inviteLink))
                }
                return links.inviteLinks?.map { it.toInviteLinkInfo(chatId) } ?: emptyList()

    }


    fun joinChatByInviteLink(link: String): ChatInfo = with(support) {
                log.info("Joining chat via invite link")

                val result = withResilience("joinChatByInviteLink") {
                    send<TdApi.ChatJoinResult>(TdApi.JoinChatByInviteLink(link))
                }
                return when (result) {
                    is TdApi.ChatJoinResultSuccess -> reads.getChat(result.chatId)
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


    fun joinPublicChat(usernameOrLink: String, expectedChatId: Long): ChatInfo = with(support) {
                log.info("Joining public chat @{}", usernameOrLink)

                val normalized = usernameOrLink.removePrefix("@").trim()
                val chat = withResilience("searchPublicChat") {
                    send<TdApi.Chat>(TdApi.SearchPublicChat(normalized))
                }
                // The username may have been reassigned since a cached resolution.
                // Never join a different chat than the one the allow-list checked.
                TdLibClientService.requireExpectedPublicChatId(chat.id, expectedChatId)
                withResilience("joinChat") { send<TdApi.Ok>(TdApi.JoinChat(chat.id)) }
                return chat.toChatInfo()

    }


    fun setChatPhoto(chatId: Long, filePath: String): Boolean = with(support) {
                log.info("Setting chat photo for chat {} from '{}'", chatId, filePath)

                val photo = TdApi.InputChatPhotoStatic(TdApi.InputFileLocal(filePath))
                withResilience("setChatPhoto") {
                    send<TdApi.Ok>(TdApi.SetChatPhoto(chatId, photo))
                }
                return true

    }


    fun deleteChatPhoto(chatId: Long): Boolean = with(support) {
                log.info("Deleting chat photo for chat {}", chatId)

                withResilience("deleteChatPhoto") {
                    send<TdApi.Ok>(TdApi.SetChatPhoto(chatId, null))
                }
                return true

    }


    // ── Drafts & profile ───────────────────────────────────────────────────
    fun saveDraft(chatId: Long, text: String, replyToMessageId: Long?): Boolean = with(support) {
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


    fun clearDraft(chatId: Long): Boolean = with(support) {
                log.info("Clearing draft on chat {}", chatId)

                withResilience("setChatDraftMessage") {
                    send<TdApi.Ok>(TdApi.SetChatDraftMessage(chatId, null, null))
                }
                return true

    }


    fun updateProfile(firstName: String?, lastName: String?, bio: String?): Boolean = with(support) {
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


    fun setProfilePhoto(filePath: String): Boolean = with(support) {
                log.info("Setting profile photo from '{}'", filePath)

                val photo = TdApi.InputChatPhotoStatic(TdApi.InputFileLocal(filePath))
                withResilience("setProfilePhoto") {
                    send<TdApi.Ok>(TdApi.SetProfilePhoto(photo, false))
                }
                return true

    }


    fun deleteProfilePhoto(photoId: Long?): Boolean = with(support) {
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


    fun processChatJoinRequest(chatId: Long, userId: Long, approve: Boolean): Boolean = with(support) {
                log.info("{} join request of user {} in chat {}", if (approve) "Approving" else "Declining", userId, chatId)
                withResilience("processChatJoinRequest") {
                    send<TdApi.Ok>(TdApi.ProcessChatJoinRequest(chatId, userId, approve))
                }
                return true

    }


    fun unpinAllMessages(chatId: Long): Boolean = with(support) {
                log.info("Unpinning all messages in chat {}", chatId)
                withResilience("unpinAllChatMessages") { send<TdApi.Ok>(TdApi.UnpinAllChatMessages(chatId)) }
                return true

    }


    fun deleteMessagesBySender(chatId: Long, senderId: Long): Boolean = with(support) {
                log.info("Deleting all messages of sender {} in chat {}", senderId, chatId)
                withResilience("deleteChatMessagesBySender") {
                    send<TdApi.Ok>(TdApi.DeleteChatMessagesBySender(chatId, TdLibRequestBuilders.messageSender(senderId)))
                }
                return true

    }
}
