package dev.telegrammcp.server.client

import dev.telegrammcp.server.exception.TelegramUnavailableException
import dev.telegrammcp.server.model.*
import dev.telegrammcp.server.util.StructuredLogger
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Provides a fallback [TelegramClientService] when TDLib is not configured.
 *
 * This bean is created only when no other [TelegramClientService] exists in the
 * context (i.e. when `tdlib.api.id` is not set). All methods throw
 * [TelegramUnavailableException] — the tools will report meaningful errors
 * instead of crashing on a missing bean.
 *
 * A [DelegatingTelegramClientService] wraps the initial implementation so that
 * the interactive auth orchestrator can hot-swap it for a real TDLib-backed
 * service after successful login — without restarting the Spring context.
 */
@Configuration
class FallbackClientConfig {

    private val log = StructuredLogger.forClass<FallbackClientConfig>()

    @Bean
    @ConditionalOnMissingBean(DelegatingTelegramClientService::class)
    fun delegatingTelegramClientService(): DelegatingTelegramClientService {
        log.warn(
            "TDLib is not configured (tdlib.api.id/hash are missing). " +
                "All Telegram tools will return errors until interactive auth completes. " +
                "Set TDLIB_API_ID and TDLIB_API_HASH to enable, or use /auth endpoints.",
        )
        return DelegatingTelegramClientService.wrapping(NoOpTelegramClientService())
    }

    @Bean
    @ConditionalOnMissingBean(TelegramClientService::class)
    fun fallbackTelegramClientService(
        delegating: DelegatingTelegramClientService,
    ): TelegramClientService = delegating.proxy
}

/**
 * Stub implementation that throws on every call.
 */
private class NoOpTelegramClientService : TelegramClientService {

    private fun unavailable(): Nothing =
        throw TelegramUnavailableException(
            IllegalStateException("TDLib client is not configured. Set tdlib.api.id and tdlib.api.hash."),
        )

    override fun getHistory(chatId: Long, fromMessageId: Long, offset: Int, limit: Int) = unavailable()
    override fun searchMessages(chatId: Long, query: String, offset: Long, limit: Int) = unavailable()
    override fun sendMessage(chatId: Long, text: String, parseMode: ParseMode, replyMarkup: ReplyMarkupSpec?, messageThreadId: Long?) = unavailable()
    override fun replyToMessage(chatId: Long, replyToMessageId: Long, text: String, parseMode: ParseMode) = unavailable()
    override fun editMessage(chatId: Long, messageId: Long, text: String, parseMode: ParseMode) = unavailable()
    override fun getChats(limit: Int) = unavailable()
    override fun getChat(chatId: Long) = unavailable()
    override fun listChatFolders() = unavailable()
    override fun getChatFolder(folderId: Int) = unavailable()
    override fun createChatFolder(folder: ChatFolderDefinition) = unavailable()
    override fun updateChatFolder(folderId: Int, folder: ChatFolderDefinition) = unavailable()
    override fun deleteChatFolder(folderId: Int) = unavailable()
    override fun reorderChatFolders(folderIds: List<Int>, mainChatListPosition: Int) = unavailable()
    override fun getChatMembers(chatId: Long, query: String, offset: Int, limit: Int) = unavailable()
    override fun getMe() = unavailable()
    override fun getContacts() = unavailable()
    override fun getUser(userId: Long) = unavailable()
    override fun getMediaInfo(chatId: Long, messageId: Long) = unavailable()
    override fun downloadMedia(chatId: Long, messageId: Long) = unavailable()
    override fun transcribeVoiceNote(chatId: Long, messageId: Long) = unavailable()
    override fun sendFile(chatId: Long, filePath: String, caption: String?) = unavailable()
    override fun deleteMessages(chatId: Long, messageIds: List<Long>, revoke: Boolean) = unavailable()
    override fun forwardMessages(fromChatId: Long, toChatId: Long, messageIds: List<Long>) = unavailable()
    override fun pinMessage(chatId: Long, messageId: Long, disableNotification: Boolean) = unavailable()
    override fun unpinMessage(chatId: Long, messageId: Long) = unavailable()
    override fun getPinnedMessages(chatId: Long) = unavailable()
    override fun viewMessages(chatId: Long, messageIds: List<Long>) = unavailable()
    override fun searchGlobal(query: String, limit: Int) = unavailable()
    override fun getScheduledMessages(chatId: Long) = unavailable()
    override fun scheduleMessage(chatId: Long, text: String, sendAtEpochSeconds: Int, repeatPeriodSeconds: Int, disableNotification: Boolean, parseMode: ParseMode) = unavailable()
    override fun rescheduleMessage(chatId: Long, messageId: Long, sendAtEpochSeconds: Int, repeatPeriodSeconds: Int) = unavailable()
    override fun cancelScheduledMessage(chatId: Long, messageId: Long) = unavailable()
    override fun createBasicGroup(title: String, userIds: List<Long>) = unavailable()
    override fun addChatMembers(chatId: Long, userIds: List<Long>) = unavailable()
    override fun leaveChat(chatId: Long) = unavailable()
    override fun banChatMember(chatId: Long, userId: Long) = unavailable()
    override fun unbanChatMember(chatId: Long, userId: Long) = unavailable()
    override fun setChatMemberAdmin(chatId: Long, userId: Long, isAdmin: Boolean) = unavailable()
    override fun getPrivacySettingRules(setting: PrivacySetting) = unavailable()
    override fun setPrivacySettingRules(rules: PrivacySettingRules) = unavailable()
    override fun getBotCommands(scope: BotCommandScope, languageCode: String) = unavailable()
    override fun setBotCommands(scope: BotCommandScope, languageCode: String, commands: List<BotCommand>) = unavailable()
    override fun getChatPermissions(chatId: Long) = unavailable()
    override fun setChatPermissions(chatId: Long, permissions: ChatPermissions) = unavailable()
    override fun setChatMemberPermissions(chatId: Long, userId: Long, permissions: ChatPermissions, restrictedUntilDate: Int, isMember: Boolean) = unavailable()
    override fun setChatMemberAdministratorRights(chatId: Long, userId: Long, rights: ChatAdministratorRights) = unavailable()
    override fun setChatTitle(chatId: Long, title: String) = unavailable()
    override fun setChatDescription(chatId: Long, description: String) = unavailable()
    override fun setChatSlowModeDelay(chatId: Long, delaySeconds: Int) = unavailable()
    override fun archiveChat(chatId: Long) = unavailable()
    override fun unarchiveChat(chatId: Long) = unavailable()
    override fun muteChat(chatId: Long, muteFor: Int) = unavailable()
    override fun unmuteChat(chatId: Long) = unavailable()
    override fun addContact(userId: Long, firstName: String, lastName: String?, phoneNumber: String?) = unavailable()
    override fun removeContacts(userIds: List<Long>) = unavailable()
    override fun blockUser(userId: Long) = unavailable()
    override fun unblockUser(userId: Long) = unavailable()
    override fun resolveUsername(username: String) = unavailable()
    override fun resolvePhone(phoneNumber: String) = unavailable()
    override fun resolveSelfChat() = unavailable()
    override fun addReaction(chatId: Long, messageId: Long, emoji: String, isBig: Boolean) = unavailable()
    override fun removeReaction(chatId: Long, messageId: Long, emoji: String) = unavailable()
    override fun getMessageReactions(chatId: Long, messageId: Long, limit: Int) = unavailable()
    override fun sendPoll(chatId: Long, question: String, options: List<String>, isAnonymous: Boolean, allowMultipleAnswers: Boolean) = unavailable()
    override fun setPollAnswer(chatId: Long, messageId: Long, optionIds: List<Int>) = unavailable()
    override fun stopPoll(chatId: Long, messageId: Long) = unavailable()
    override fun getMessageViewers(chatId: Long, messageId: Long) = unavailable()
    override fun getMessageContext(chatId: Long, messageId: Long, contextSize: Int) = unavailable()
    override fun listInlineButtons(chatId: Long, messageId: Long) = unavailable()
    override fun pressInlineButton(chatId: Long, messageId: Long, buttonIndex: Int?, buttonText: String?) = unavailable()
    override fun listForumTopics(chatId: Long, query: String, limit: Int) = unavailable()
    override fun createForumTopic(chatId: Long, name: String, iconColor: Int?, customEmojiId: Long?) = unavailable()
    override fun editForumTopic(
        chatId: Long,
        messageThreadId: Long,
        name: String?,
        editIconCustomEmoji: Boolean,
        customEmojiId: Long?,
    ) = unavailable()
    override fun closeForumTopic(chatId: Long, messageThreadId: Long) = unavailable()
    override fun reopenForumTopic(chatId: Long, messageThreadId: Long) = unavailable()
    override fun setForumTopicsEnabled(chatId: Long, enabled: Boolean, hasForumTabs: Boolean) = unavailable()
    override fun createSupergroupOrChannel(title: String, description: String, isSupergroup: Boolean, isForum: Boolean) = unavailable()
    override fun createInviteLink(chatId: Long) = unavailable()
    override fun getChatInviteLinks(chatId: Long, includeRevoked: Boolean, limit: Int) = unavailable()
    override fun revokeChatInviteLink(chatId: Long, inviteLink: String) = unavailable()
    override fun joinChatByInviteLink(link: String) = unavailable()
    override fun joinPublicChat(usernameOrLink: String) = unavailable()
    override fun getChatAdmins(chatId: Long, limit: Int) = unavailable()
    override fun getBannedChatMembers(chatId: Long, limit: Int) = unavailable()
    override fun sendVoice(chatId: Long, filePath: String, duration: Int, caption: String?) = unavailable()
    override fun sendSticker(chatId: Long, filePath: String, emoji: String?) = unavailable()
    override fun getInstalledStickerSets(limit: Int) = unavailable()
    override fun setChatPhoto(chatId: Long, filePath: String) = unavailable()
    override fun deleteChatPhoto(chatId: Long) = unavailable()
    override fun saveDraft(chatId: Long, text: String, replyToMessageId: Long?) = unavailable()
    override fun getDrafts() = unavailable()
    override fun clearDraft(chatId: Long) = unavailable()
    override fun updateProfile(firstName: String?, lastName: String?, bio: String?) = unavailable()
    override fun setProfilePhoto(filePath: String) = unavailable()
    override fun deleteProfilePhoto(photoId: Long?) = unavailable()
    override fun getUserProfilePhotos(userId: Long, limit: Int) = unavailable()
    override fun searchPublicChats(query: String, limit: Int) = unavailable()
    override fun searchContactsByQuery(query: String, limit: Int) = unavailable()
    override fun getBlockedUsers(limit: Int) = unavailable()
    override fun getUserStatus(userId: Long) = unavailable()
    override fun getChatEventLog(chatId: Long, limit: Int) = unavailable()
    override fun getMessageByLink(link: String) = unavailable()
    override fun getMessageLink(chatId: Long, messageId: Long, forAlbum: Boolean, inMessageThread: Boolean) = unavailable()
    override fun getLastInteractionWithContact(contactId: Long) = unavailable()
    override fun getGroupsInCommon(userId: Long, limit: Int) = unavailable()
}

