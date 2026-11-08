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
 * TDLib-backed implementation of [TelegramClientService].
 *
 * Read, message, and admin calls live in [TdLibReadAdapter], [TdLibMessageAdapter], and
 * [TdLibAdminAdapter]. They share one [TdLibRequestExecutor], which owns flood control,
 * deadlines, the circuit breaker, and the bulkhead.
 */
class TdLibClientService(
    client: SimpleTelegramClient,
    rateLimiter: RateLimiter,
    circuitBreaker: CircuitBreaker,
    meterRegistry: MeterRegistry,
    chatFolderState: ChatFolderState = ChatFolderState(),
    messageSendTracker: MessageSendTracker? = null,
    floodGate: TelegramFloodGate = TelegramFloodGate(),
    requestSettings: TdLibProperties.Requests = TdLibProperties.Requests(),
    bulkhead: Bulkhead = accountBulkhead("telegram", requestSettings),
    /** Decides whether a full request timeout counts toward [circuitBreaker]. */
    internal val timeoutHealth: TdLibTimeoutHealth = TdLibTimeoutHealth.alwaysCounting(),
) : TelegramClientService {

    private val support = TdLibClientSupport(
        client, rateLimiter, circuitBreaker, meterRegistry, chatFolderState, messageSendTracker,
        floodGate, requestSettings, bulkhead, timeoutHealth,
    )
    private val reads = TdLibReadAdapter(support)
    private val messages = TdLibMessageAdapter(support, reads)
    private val admin = TdLibAdminAdapter(support, reads)

    companion object {
        /**
         * Per-account bulkhead (see [TdLibProperties.Requests.maxConcurrentCalls]).
         * It fails fast instead of queueing: a waiting caller would only hold a
         * server thread longer and reach the MCP client's timeout anyway.
         */
        fun accountBulkhead(name: String, settings: TdLibProperties.Requests): Bulkhead = Bulkhead.of(
            name,
            BulkheadConfig.custom()
                .maxConcurrentCalls(settings.maxConcurrentCalls)
                .maxWaitDuration(Duration.ZERO)
                .build(),
        )

        internal fun requireExpectedPublicChatId(actualChatId: Long, expectedChatId: Long) {
            if (actualChatId != expectedChatId) throw ChatNotAllowedException()
        }
    }

    override fun getHistory(chatId: Long, fromMessageId: Long, offset: Int, limit: Int): List<TelegramMessage> = reads.getHistory(chatId, fromMessageId, offset, limit)
    override fun searchMessages(chatId: Long, query: String, offset: Long, limit: Int): List<TelegramMessage> = reads.searchMessages(chatId, query, offset, limit)
    override fun getChats(limit: Int): List<ChatInfo> = reads.getChats(limit)
    override fun getChat(chatId: Long): ChatInfo = reads.getChat(chatId)
    override fun listChatFolders(): ChatFolderListing = reads.listChatFolders()
    override fun getChatFolder(folderId: Int): ChatFolderDetails = reads.getChatFolder(folderId)
    override fun getChatMembers(chatId: Long, query: String, offset: Int, limit: Int): List<ChatMember> = reads.getChatMembers(chatId, query, offset, limit)
    override fun getMe(): UserInfo = reads.getMe()
    override fun getContacts(): List<ContactInfo> = reads.getContacts()
    override fun getUser(userId: Long): UserInfo = reads.getUser(userId)
    override fun getMediaInfo(chatId: Long, messageId: Long): MediaInfo = reads.getMediaInfo(chatId, messageId)
    override fun downloadMedia(chatId: Long, messageId: Long): DownloadResult = reads.downloadMedia(chatId, messageId)
    override fun transcribeVoiceNote(chatId: Long, messageId: Long): VoiceTranscription = reads.transcribeVoiceNote(chatId, messageId)
    override fun getPinnedMessages(chatId: Long): List<TelegramMessage> = reads.getPinnedMessages(chatId)
    override fun viewMessages(chatId: Long, messageIds: List<Long>): Boolean = reads.viewMessages(chatId, messageIds)
    override fun searchGlobal(query: String, limit: Int): List<TelegramMessage> = reads.searchGlobal(query, limit)
    override fun getScheduledMessages(chatId: Long): List<ScheduledMessage> = reads.getScheduledMessages(chatId)
    override fun getPrivacySettingRules(setting: PrivacySetting): PrivacySettingRules = reads.getPrivacySettingRules(setting)
    override fun getBotCommands(scope: BotCommandScope, languageCode: String): List<BotCommand> = reads.getBotCommands(scope, languageCode)
    override fun getChatPermissions(chatId: Long): ChatPermissions = reads.getChatPermissions(chatId)
    override fun resolveUsername(username: String): Long = reads.resolveUsername(username)
    override fun resolvePhone(phoneNumber: String): Long = reads.resolvePhone(phoneNumber)
    override fun resolveSelfChat(): Long = reads.resolveSelfChat()
    override fun getMessageReactions(chatId: Long, messageId: Long, limit: Int): MessageReactionSummary = reads.getMessageReactions(chatId, messageId, limit)
    override fun getMessageViewers(chatId: Long, messageId: Long): List<MessageViewerInfo> = reads.getMessageViewers(chatId, messageId)
    override fun getMessageContext(chatId: Long, messageId: Long, contextSize: Int): List<TelegramMessage> = reads.getMessageContext(chatId, messageId, contextSize)
    override fun listInlineButtons(chatId: Long, messageId: Long): List<InlineButtonInfo> = reads.listInlineButtons(chatId, messageId)
    override fun listForumTopics(chatId: Long, query: String, limit: Int): List<ForumTopicInfoModel> = reads.listForumTopics(chatId, query, limit)
    override fun getChatInviteLinks(chatId: Long, includeRevoked: Boolean, limit: Int): List<ChatInviteLinkInfo> = reads.getChatInviteLinks(chatId, includeRevoked, limit)
    override fun getChatAdmins(chatId: Long, limit: Int): List<ChatMember> = reads.getChatAdmins(chatId, limit)
    override fun getBannedChatMembers(chatId: Long, limit: Int): List<ChatMember> = reads.getBannedChatMembers(chatId, limit)
    override fun getInstalledStickerSets(limit: Int): List<StickerSetInfo> = reads.getInstalledStickerSets(limit)
    override fun getDrafts(): List<DraftInfo> = reads.getDrafts()
    override fun getUserProfilePhotos(userId: Long, limit: Int): List<UserPhotoInfo> = reads.getUserProfilePhotos(userId, limit)
    override fun searchPublicChats(query: String, limit: Int): List<ChatInfo> = reads.searchPublicChats(query, limit)
    override fun searchContactsByQuery(query: String, limit: Int): List<ContactInfo> = reads.searchContactsByQuery(query, limit)
    override fun getBlockedUsers(limit: Int): List<UserInfo> = reads.getBlockedUsers(limit)
    override fun getUserStatus(userId: Long): String = reads.getUserStatus(userId)
    override fun getChatEventLog(chatId: Long, limit: Int): List<AdminActionInfo> = reads.getChatEventLog(chatId, limit)
    override fun getMessageByLink(link: String): TelegramMessage = reads.getMessageByLink(link)
    override fun getMessageLink(chatId: Long, messageId: Long, forAlbum: Boolean, inMessageThread: Boolean): TelegramMessageLink = reads.getMessageLink(chatId, messageId, forAlbum, inMessageThread)
    override fun getLastInteractionWithContact(contactId: Long): TelegramMessage? = reads.getLastInteractionWithContact(contactId)
    override fun getGroupsInCommon(userId: Long, limit: Int): List<ChatInfo> = reads.getGroupsInCommon(userId, limit)
    override fun getChatJoinRequests(chatId: Long, query: String, inviteLink: String, limit: Int): ChatJoinRequestList = reads.getChatJoinRequests(chatId, query, inviteLink, limit)
    override fun getChatStatistics(chatId: Long): ChatStatisticsSummary = reads.getChatStatistics(chatId)
    override fun sendMessage(chatId: Long, text: String, parseMode: ParseMode, replyMarkup: ReplyMarkupSpec?, messageThreadId: Long?): TelegramMessage = messages.sendMessage(chatId, text, parseMode, replyMarkup, messageThreadId)
    override fun replyToMessage(chatId: Long, replyToMessageId: Long, text: String, parseMode: ParseMode): TelegramMessage = messages.replyToMessage(chatId, replyToMessageId, text, parseMode)
    override fun editMessage(chatId: Long, messageId: Long, text: String, parseMode: ParseMode): TelegramMessage = messages.editMessage(chatId, messageId, text, parseMode)
    override fun sendFile(chatId: Long, filePath: String, caption: String?): TelegramMessage = messages.sendFile(chatId, filePath, caption)
    override fun deleteMessages(chatId: Long, messageIds: List<Long>, revoke: Boolean): Boolean = messages.deleteMessages(chatId, messageIds, revoke)
    override fun forwardMessages(fromChatId: Long, toChatId: Long, messageIds: List<Long>): List<TelegramMessage> = messages.forwardMessages(fromChatId, toChatId, messageIds)
    override fun pinMessage(chatId: Long, messageId: Long, disableNotification: Boolean): Boolean = messages.pinMessage(chatId, messageId, disableNotification)
    override fun unpinMessage(chatId: Long, messageId: Long): Boolean = messages.unpinMessage(chatId, messageId)
    override fun scheduleMessage(chatId: Long, text: String, sendAtEpochSeconds: Int, repeatPeriodSeconds: Int, disableNotification: Boolean, parseMode: ParseMode): ScheduledMessage = messages.scheduleMessage(chatId, text, sendAtEpochSeconds, repeatPeriodSeconds, disableNotification, parseMode)
    override fun rescheduleMessage(chatId: Long, messageId: Long, sendAtEpochSeconds: Int, repeatPeriodSeconds: Int): ScheduledMessage = messages.rescheduleMessage(chatId, messageId, sendAtEpochSeconds, repeatPeriodSeconds)
    override fun cancelScheduledMessage(chatId: Long, messageId: Long): Boolean = messages.cancelScheduledMessage(chatId, messageId)
    override fun addReaction(chatId: Long, messageId: Long, emoji: String, isBig: Boolean): Boolean = messages.addReaction(chatId, messageId, emoji, isBig)
    override fun removeReaction(chatId: Long, messageId: Long, emoji: String): Boolean = messages.removeReaction(chatId, messageId, emoji)
    override fun sendPoll(chatId: Long, question: String, options: List<String>, isAnonymous: Boolean, allowMultipleAnswers: Boolean): TelegramMessage = messages.sendPoll(chatId, question, options, isAnonymous, allowMultipleAnswers)
    override fun setPollAnswer(chatId: Long, messageId: Long, optionIds: List<Int>): Boolean = messages.setPollAnswer(chatId, messageId, optionIds)
    override fun stopPoll(chatId: Long, messageId: Long): Boolean = messages.stopPoll(chatId, messageId)
    override fun pressInlineButton(chatId: Long, messageId: Long, buttonIndex: Int?, buttonText: String?): String = messages.pressInlineButton(chatId, messageId, buttonIndex, buttonText)
    override fun sendVoice(chatId: Long, filePath: String, duration: Int, caption: String?): TelegramMessage = messages.sendVoice(chatId, filePath, duration, caption)
    override fun sendSticker(chatId: Long, filePath: String, emoji: String?): TelegramMessage = messages.sendSticker(chatId, filePath, emoji)
    override fun sendAlbum(chatId: Long, items: List<AlbumItem>): List<TelegramMessage> = messages.sendAlbum(chatId, items)
    override fun sendChatAction(chatId: Long, action: ChatActionType): Boolean = messages.sendChatAction(chatId, action)
    override fun sendLocation(chatId: Long, latitude: Double, longitude: Double, horizontalAccuracy: Double): TelegramMessage = messages.sendLocation(chatId, latitude, longitude, horizontalAccuracy)
    override fun sendVenue(chatId: Long, venue: VenueSpec): TelegramMessage = messages.sendVenue(chatId, venue)
    override fun sendContact(chatId: Long, contact: ContactSpec): TelegramMessage = messages.sendContact(chatId, contact)
    override fun createChatFolder(folder: ChatFolderDefinition): ChatFolderInfo = admin.createChatFolder(folder)
    override fun updateChatFolder(folderId: Int, folder: ChatFolderDefinition): ChatFolderInfo = admin.updateChatFolder(folderId, folder)
    override fun deleteChatFolder(folderId: Int): Boolean = admin.deleteChatFolder(folderId)
    override fun reorderChatFolders(folderIds: List<Int>, mainChatListPosition: Int): Boolean = admin.reorderChatFolders(folderIds, mainChatListPosition)
    override fun createBasicGroup(title: String, userIds: List<Long>): ChatInfo = admin.createBasicGroup(title, userIds)
    override fun addChatMembers(chatId: Long, userIds: List<Long>): Boolean = admin.addChatMembers(chatId, userIds)
    override fun leaveChat(chatId: Long): Boolean = admin.leaveChat(chatId)
    override fun banChatMember(chatId: Long, userId: Long): Boolean = admin.banChatMember(chatId, userId)
    override fun unbanChatMember(chatId: Long, userId: Long): Boolean = admin.unbanChatMember(chatId, userId)
    override fun setChatMemberAdmin(chatId: Long, userId: Long, isAdmin: Boolean): Boolean = admin.setChatMemberAdmin(chatId, userId, isAdmin)
    override fun setPrivacySettingRules(rules: PrivacySettingRules): Boolean = admin.setPrivacySettingRules(rules)
    override fun setBotCommands(scope: BotCommandScope, languageCode: String, commands: List<BotCommand>): Boolean = admin.setBotCommands(scope, languageCode, commands)
    override fun setChatPermissions(chatId: Long, permissions: ChatPermissions): Boolean = admin.setChatPermissions(chatId, permissions)
    override fun setChatMemberPermissions(chatId: Long, userId: Long, permissions: ChatPermissions, restrictedUntilDate: Int, isMember: Boolean): Boolean = admin.setChatMemberPermissions(chatId, userId, permissions, restrictedUntilDate, isMember)
    override fun setChatMemberAdministratorRights(chatId: Long, userId: Long, rights: ChatAdministratorRights): Boolean = admin.setChatMemberAdministratorRights(chatId, userId, rights)
    override fun setChatTitle(chatId: Long, title: String): Boolean = admin.setChatTitle(chatId, title)
    override fun setChatDescription(chatId: Long, description: String): Boolean = admin.setChatDescription(chatId, description)
    override fun setChatSlowModeDelay(chatId: Long, delaySeconds: Int): Boolean = admin.setChatSlowModeDelay(chatId, delaySeconds)
    override fun archiveChat(chatId: Long): Boolean = admin.archiveChat(chatId)
    override fun unarchiveChat(chatId: Long): Boolean = admin.unarchiveChat(chatId)
    override fun muteChat(chatId: Long, muteFor: Int): Boolean = admin.muteChat(chatId, muteFor)
    override fun unmuteChat(chatId: Long): Boolean = admin.unmuteChat(chatId)
    override fun addContact(userId: Long, firstName: String, lastName: String?, phoneNumber: String?): Boolean = admin.addContact(userId, firstName, lastName, phoneNumber)
    override fun removeContacts(userIds: List<Long>): Boolean = admin.removeContacts(userIds)
    override fun blockUser(userId: Long): Boolean = admin.blockUser(userId)
    override fun unblockUser(userId: Long): Boolean = admin.unblockUser(userId)
    override fun createForumTopic(chatId: Long, name: String, iconColor: Int?, customEmojiId: Long?): ForumTopicInfoModel = admin.createForumTopic(chatId, name, iconColor, customEmojiId)
    override fun editForumTopic(chatId: Long, messageThreadId: Long, name: String?, editIconCustomEmoji: Boolean, customEmojiId: Long?): Boolean = admin.editForumTopic(chatId, messageThreadId, name, editIconCustomEmoji, customEmojiId)
    override fun closeForumTopic(chatId: Long, messageThreadId: Long): Boolean = admin.closeForumTopic(chatId, messageThreadId)
    override fun reopenForumTopic(chatId: Long, messageThreadId: Long): Boolean = admin.reopenForumTopic(chatId, messageThreadId)
    override fun setForumTopicsEnabled(chatId: Long, enabled: Boolean, hasForumTabs: Boolean): Boolean = admin.setForumTopicsEnabled(chatId, enabled, hasForumTabs)
    override fun createSupergroupOrChannel(title: String, description: String, isSupergroup: Boolean, isForum: Boolean): ChatInfo = admin.createSupergroupOrChannel(title, description, isSupergroup, isForum)
    override fun createInviteLink(chatId: Long): ChatInviteLinkInfo = admin.createInviteLink(chatId)
    override fun revokeChatInviteLink(chatId: Long, inviteLink: String): List<ChatInviteLinkInfo> = admin.revokeChatInviteLink(chatId, inviteLink)
    override fun joinChatByInviteLink(link: String): ChatInfo = admin.joinChatByInviteLink(link)
    override fun joinPublicChat(usernameOrLink: String, expectedChatId: Long): ChatInfo = admin.joinPublicChat(usernameOrLink, expectedChatId)
    override fun setChatPhoto(chatId: Long, filePath: String): Boolean = admin.setChatPhoto(chatId, filePath)
    override fun deleteChatPhoto(chatId: Long): Boolean = admin.deleteChatPhoto(chatId)
    override fun saveDraft(chatId: Long, text: String, replyToMessageId: Long?): Boolean = admin.saveDraft(chatId, text, replyToMessageId)
    override fun clearDraft(chatId: Long): Boolean = admin.clearDraft(chatId)
    override fun updateProfile(firstName: String?, lastName: String?, bio: String?): Boolean = admin.updateProfile(firstName, lastName, bio)
    override fun setProfilePhoto(filePath: String): Boolean = admin.setProfilePhoto(filePath)
    override fun deleteProfilePhoto(photoId: Long?): Boolean = admin.deleteProfilePhoto(photoId)
    override fun processChatJoinRequest(chatId: Long, userId: Long, approve: Boolean): Boolean = admin.processChatJoinRequest(chatId, userId, approve)
    override fun unpinAllMessages(chatId: Long): Boolean = admin.unpinAllMessages(chatId)
    override fun deleteMessagesBySender(chatId: Long, senderId: Long): Boolean = admin.deleteMessagesBySender(chatId, senderId)
}

internal data class FailedMemberAdd(
    val userId: Long,
    val premiumWouldAllowInvite: Boolean,
    val premiumRequiredToSendMessages: Boolean,
)

internal fun TdApi.FailedToAddMembers?.toFailedMemberAdds(): List<FailedMemberAdd> =
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
