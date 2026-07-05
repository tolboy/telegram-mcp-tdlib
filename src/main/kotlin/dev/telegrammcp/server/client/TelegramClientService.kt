package dev.telegrammcp.server.client

import dev.telegrammcp.server.model.*

/**
 * Abstraction over the Telegram client (TDLib).
 *
 * All tool implementations depend on this interface, never on TDLib directly.
 * This allows easy mocking in tests and potential future alternative
 * implementations (e.g. Bot API fallback).
 */
interface TelegramClientService {

    // ── Messages ────────────────────────────────────────────────────────────

    /**
     * Retrieves chat history (recent messages).
     *
     * @param chatId         numeric chat ID
     * @param fromMessageId  start from this message ID (0 = latest)
     * @param offset         offset from [fromMessageId] (negative = older)
     * @param limit          max number of messages to return
     */
    fun getHistory(chatId: Long, fromMessageId: Long = 0, offset: Int = 0, limit: Int = 20): List<TelegramMessage>

    /**
     * Searches messages in a specific chat.
     *
     * @param chatId  numeric chat ID
     * @param query   search string
     * @param offset  pagination offset
     * @param limit   max results
     */
    fun searchMessages(chatId: Long, query: String, offset: Long = 0, limit: Int = 20): List<TelegramMessage>

    /**
     * Sends a text message to a chat.
     *
     * @param chatId      numeric chat ID
     * @param text        message text
     * @param parseMode   text formatting mode
     * @param replyMarkup optional keyboard markup (reply keyboard or remove keyboard)
     * @param messageThreadId optional forum topic message thread ID
     * @return the sent message
     */
    fun sendMessage(
        chatId: Long,
        text: String,
        parseMode: ParseMode = ParseMode.PLAIN,
        replyMarkup: ReplyMarkupSpec? = null,
        messageThreadId: Long? = null,
    ): TelegramMessage

    /**
     * Replies to a specific message.
     *
     * @param chatId         numeric chat ID
     * @param replyToMessageId the message to reply to
     * @param text           reply text
     * @param parseMode      text formatting mode
     * @return the sent reply message
     */
    fun replyToMessage(
        chatId: Long,
        replyToMessageId: Long,
        text: String,
        parseMode: ParseMode = ParseMode.PLAIN,
    ): TelegramMessage

    /**
     * Edits the text of an existing message.
     *
     * @param chatId    numeric chat ID
     * @param messageId the message to edit
     * @param text      new text content
     * @param parseMode text formatting mode
     * @return the edited message
     */
    fun editMessage(chatId: Long, messageId: Long, text: String, parseMode: ParseMode = ParseMode.PLAIN): TelegramMessage

    // ── Chats ───────────────────────────────────────────────────────────────

    /**
     * Retrieves the list of chats the user/bot is part of.
     *
     * @param limit max number of chats
     * @return list of chats ordered by last message date
     */
    fun getChats(limit: Int = 50): List<ChatInfo>

    /**
     * Retrieves detailed information about a single chat.
     *
     * @param chatId numeric chat ID
     */
    fun getChat(chatId: Long): ChatInfo

    /** Lists the account's known chat folders from TDLib's folder update state. */
    fun listChatFolders(): ChatFolderListing

    /** Retrieves the complete configuration for one Telegram chat folder. */
    fun getChatFolder(folderId: Int): ChatFolderDetails

    /** Creates a chat folder and returns its TDLib-assigned ID. */
    fun createChatFolder(folder: ChatFolderDefinition): ChatFolderInfo

    /** Replaces the complete configuration for an existing chat folder. */
    fun updateChatFolder(folderId: Int, folder: ChatFolderDefinition): ChatFolderInfo

    /** Deletes a chat folder without leaving any of its included chats. */
    fun deleteChatFolder(folderId: Int): Boolean

    /**
     * Reorders the account's chat folders to the given complete ID sequence.
     *
     * @param mainChatListPosition 0-based position of the main chat list among the folders
     */
    fun reorderChatFolders(folderIds: List<Int>, mainChatListPosition: Int = 0): Boolean

    /**
     * Retrieves members of a group/supergroup/channel.
     *
     * @param chatId  numeric chat ID
     * @param query   optional filter by name/username
     * @param offset  pagination offset
     * @param limit   max results
     */
    fun getChatMembers(chatId: Long, query: String = "", offset: Int = 0, limit: Int = 50): List<ChatMember>

    // ── Users & Contacts ────────────────────────────────────────────────────

    /**
     * Retrieves the profile of the currently authenticated user/bot.
     *
     * @return current user's profile information
     */
    fun getMe(): UserInfo

    /**
     * Retrieves the list of contacts in the user's address book.
     *
     * @return list of contacts
     */
    fun getContacts(): List<ContactInfo>

    /**
     * Retrieves detailed user information by user ID.
     *
     * @param userId numeric Telegram user ID
     * @return user profile information
     */
    fun getUser(userId: Long): UserInfo

    // ── Media ───────────────────────────────────────────────────────────────

    /**
     * Retrieves metadata for media attached to a message.
     *
     * @param chatId    numeric chat ID
     * @param messageId the message containing media
     * @return media metadata
     */
    fun getMediaInfo(chatId: Long, messageId: Long): MediaInfo

    /**
     * Downloads media from a message to a local file path.
     *
     * @param chatId    numeric chat ID
     * @param messageId the message containing media
     * @return download result with local path and metadata
     */
    fun downloadMedia(chatId: Long, messageId: Long): DownloadResult

    /**
     * Requests Telegram's native transcription for a voice note and waits for
     * a completed result when Telegram returns it promptly.
     *
     * The Telegram account must have the appropriate Premium or trial access.
     * The operation never sends a new Telegram message.
     */
    fun transcribeVoiceNote(chatId: Long, messageId: Long): VoiceTranscription

    /**
     * Sends a file to a chat.
     *
     * @param chatId   numeric chat ID
     * @param filePath local file path to upload
     * @param caption  optional caption for the file
     * @return the sent message
     */
    fun sendFile(chatId: Long, filePath: String, caption: String? = null): TelegramMessage

    // ── Extended message operations ────────────────────────────────────────

    /**
     * Deletes messages from a chat.
     *
     * @param chatId     numeric chat ID
     * @param messageIds list of message IDs to delete
     * @param revoke     whether to delete for all participants
     * @return true if successful
     */
    fun deleteMessages(chatId: Long, messageIds: List<Long>, revoke: Boolean = true): Boolean

    /**
     * Forwards messages from one chat to another.
     *
     * @param fromChatId source chat ID
     * @param toChatId   destination chat ID
     * @param messageIds message IDs to forward
     * @return the forwarded messages
     */
    fun forwardMessages(fromChatId: Long, toChatId: Long, messageIds: List<Long>): List<TelegramMessage>

    /**
     * Pins a message in a chat.
     *
     * @param chatId              numeric chat ID
     * @param messageId           message to pin
     * @param disableNotification if true, no notification is sent
     * @return true if successful
     */
    fun pinMessage(chatId: Long, messageId: Long, disableNotification: Boolean = false): Boolean

    /**
     * Unpins a message in a chat.
     *
     * @param chatId    numeric chat ID
     * @param messageId message to unpin
     * @return true if successful
     */
    fun unpinMessage(chatId: Long, messageId: Long): Boolean

    /**
     * Retrieves pinned messages in a chat.
     *
     * @param chatId numeric chat ID
     * @return list of pinned messages
     */
    fun getPinnedMessages(chatId: Long): List<TelegramMessage>

    /**
     * Marks messages as read in a chat.
     *
     * @param chatId     numeric chat ID
     * @param messageIds messages to mark as read (empty = mark all as read)
     * @return true if successful
     */
    fun viewMessages(chatId: Long, messageIds: List<Long>): Boolean

    /**
     * Searches messages across all chats.
     *
     * @param query  search string
     * @param limit  max results
     * @return matching messages from all chats
     */
    fun searchGlobal(query: String, limit: Int = 20): List<TelegramMessage>

    /** Lists messages currently queued by Telegram for one chat. */
    fun getScheduledMessages(chatId: Long): List<ScheduledMessage>

    /** Queues a text message for a future send date. */
    fun scheduleMessage(
        chatId: Long,
        text: String,
        sendAtEpochSeconds: Int,
        repeatPeriodSeconds: Int = 0,
        disableNotification: Boolean = false,
        parseMode: ParseMode = ParseMode.PLAIN,
    ): ScheduledMessage

    /** Changes the time at which an existing scheduled message will be sent. */
    fun rescheduleMessage(
        chatId: Long,
        messageId: Long,
        sendAtEpochSeconds: Int,
        repeatPeriodSeconds: Int = 0,
    ): ScheduledMessage

    /** Cancels one scheduled message before Telegram sends it. */
    fun cancelScheduledMessage(chatId: Long, messageId: Long): Boolean

    // ── Chat management ────────────────────────────────────────────────────

    /**
     * Creates a new basic group with the given users.
     *
     * @param title   group title
     * @param userIds initial member user IDs
     * @return the created chat info
     */
    fun createBasicGroup(title: String, userIds: List<Long>): ChatInfo

    /**
     * Adds members to an existing chat.
     *
     * @param chatId  numeric chat ID
     * @param userIds users to add
     * @return true if successful
     */
    fun addChatMembers(chatId: Long, userIds: List<Long>): Boolean

    /**
     * Leaves a chat.
     *
     * @param chatId numeric chat ID
     * @return true if successful
     */
    fun leaveChat(chatId: Long): Boolean

    /**
     * Bans a user from a chat.
     *
     * @param chatId numeric chat ID
     * @param userId user to ban
     * @return true if successful
     */
    fun banChatMember(chatId: Long, userId: Long): Boolean

    /**
     * Unbans a user from a chat.
     *
     * @param chatId numeric chat ID
     * @param userId user to unban
     * @return true if successful
     */
    fun unbanChatMember(chatId: Long, userId: Long): Boolean

    /**
     * Promotes or demotes a chat member to/from admin.
     *
     * @param chatId  numeric chat ID
     * @param userId  user to promote/demote
     * @param isAdmin true to promote, false to demote
     * @return true if successful
     */
    fun setChatMemberAdmin(chatId: Long, userId: Long, isAdmin: Boolean): Boolean

    /** Retrieves the current rules for one account privacy setting. */
    fun getPrivacySettingRules(setting: PrivacySetting): PrivacySettingRules

    /** Replaces the complete rules for one account privacy setting. */
    fun setPrivacySettingRules(rules: PrivacySettingRules): Boolean

    /** Retrieves a bot's command menu for a language and audience scope. */
    fun getBotCommands(scope: BotCommandScope, languageCode: String = ""): List<BotCommand>

    /** Replaces a bot's command menu for a language and audience scope. */
    fun setBotCommands(scope: BotCommandScope, languageCode: String, commands: List<BotCommand>): Boolean

    /** Retrieves a group's default member permissions. */
    fun getChatPermissions(chatId: Long): ChatPermissions

    /** Replaces a group's default member permissions. */
    fun setChatPermissions(chatId: Long, permissions: ChatPermissions): Boolean

    /** Sets member-specific restrictions without banning the member. */
    fun setChatMemberPermissions(
        chatId: Long,
        userId: Long,
        permissions: ChatPermissions,
        restrictedUntilDate: Int = 0,
        isMember: Boolean = true,
    ): Boolean

    /** Replaces a member's detailed administrator privileges. */
    fun setChatMemberAdministratorRights(chatId: Long, userId: Long, rights: ChatAdministratorRights): Boolean

    /**
     * Changes the title of a chat.
     *
     * @param chatId numeric chat ID
     * @param title  new title
     * @return true if successful
     */
    fun setChatTitle(chatId: Long, title: String): Boolean

    /** Replaces the description/about text of a group, supergroup, or channel. */
    fun setChatDescription(chatId: Long, description: String): Boolean

    /**
     * Sets the supergroup slow-mode delay in seconds.
     * Telegram accepts 0 (off), 10, 30, 60, 300, 900, or 3600.
     */
    fun setChatSlowModeDelay(chatId: Long, delaySeconds: Int): Boolean

    /**
     * Archives a chat.
     *
     * @param chatId numeric chat ID
     * @return true if successful
     */
    fun archiveChat(chatId: Long): Boolean

    /**
     * Unarchives a chat.
     *
     * @param chatId numeric chat ID
     * @return true if successful
     */
    fun unarchiveChat(chatId: Long): Boolean

    /**
     * Mutes a chat for a specified duration.
     *
     * @param chatId  numeric chat ID
     * @param muteFor duration in seconds (Int.MAX_VALUE = forever)
     * @return true if successful
     */
    fun muteChat(chatId: Long, muteFor: Int = Int.MAX_VALUE): Boolean

    /**
     * Unmutes a chat.
     *
     * @param chatId numeric chat ID
     * @return true if successful
     */
    fun unmuteChat(chatId: Long): Boolean

    // ── Contact management ─────────────────────────────────────────────────

    /**
     * Adds a contact to the user's address book.
     *
     * @param userId      Telegram user ID
     * @param firstName   first name
     * @param lastName    optional last name
     * @param phoneNumber optional phone number
     * @return true if successful
     */
    fun addContact(userId: Long, firstName: String, lastName: String? = null, phoneNumber: String? = null): Boolean

    /**
     * Removes contacts from the address book.
     *
     * @param userIds user IDs to remove
     * @return true if successful
     */
    fun removeContacts(userIds: List<Long>): Boolean

    /**
     * Blocks a user.
     *
     * @param userId user to block
     * @return true if successful
     */
    fun blockUser(userId: Long): Boolean

    /**
     * Unblocks a user.
     *
     * @param userId user to unblock
     * @return true if successful
     */
    fun unblockUser(userId: Long): Boolean

    // ── Entity resolution ───────────────────────────────────────────────────

    /**
     * Resolves a @username to a numeric chat ID.
     *
     * @param username the username without `@` prefix
     * @return numeric chat ID
     */
    fun resolveUsername(username: String): Long

    /**
     * Resolves a phone number to a numeric user ID.
     *
     * @param phoneNumber international format phone number
     * @return numeric user ID
     */
    fun resolvePhone(phoneNumber: String): Long

    /**
     * Resolves the current account's Saved Messages dialog to its numeric chat ID.
     *
     * @return numeric self chat ID
     */
    fun resolveSelfChat(): Long

    // ── Reactions & Polls ──────────────────────────────────────────────────

    /** Adds an emoji reaction to a message. */
    fun addReaction(chatId: Long, messageId: Long, emoji: String, isBig: Boolean = false): Boolean

    /** Removes a previously placed emoji reaction from a message. */
    fun removeReaction(chatId: Long, messageId: Long, emoji: String): Boolean

    /** Returns added reactions for a message (who placed what). */
    fun getMessageReactions(chatId: Long, messageId: Long, limit: Int = 50): MessageReactionSummary

    /** Sends a poll to a chat. */
    fun sendPoll(
        chatId: Long,
        question: String,
        options: List<String>,
        isAnonymous: Boolean = true,
        allowMultipleAnswers: Boolean = false,
    ): TelegramMessage

    /**
     * Votes in a poll by selecting the given 0-based option indexes.
     * An empty [optionIds] list retracts the current vote where Telegram allows it.
     */
    fun setPollAnswer(chatId: Long, messageId: Long, optionIds: List<Int>): Boolean

    /** Permanently closes a poll so no further votes are accepted. */
    fun stopPoll(chatId: Long, messageId: Long): Boolean

    /**
     * Returns users who viewed a message, where Telegram exposes read receipts
     * (small groups; recent outgoing messages).
     */
    fun getMessageViewers(chatId: Long, messageId: Long): List<MessageViewerInfo>

    /** Fetches messages around a target message (for context). */
    fun getMessageContext(chatId: Long, messageId: Long, contextSize: Int = 6): List<TelegramMessage>

    // ── Inline buttons & Forum topics ──────────────────────────────────────

    /** Inspects inline-keyboard buttons attached to a message. */
    fun listInlineButtons(chatId: Long, messageId: Long): List<InlineButtonInfo>

    /**
     * Presses a callback-data inline button.
     *
     * @return answer text returned by the bot (may be empty) or an opened URL for URL-typed buttons
     */
    fun pressInlineButton(chatId: Long, messageId: Long, buttonIndex: Int?, buttonText: String?): String

    /** Lists forum topics of a supergroup. */
    fun listForumTopics(chatId: Long, query: String = "", limit: Int = 50): List<ForumTopicInfoModel>

    /** Creates a forum topic in a forum-enabled supergroup. */
    fun createForumTopic(
        chatId: Long,
        name: String,
        iconColor: Int? = null,
        customEmojiId: Long? = null,
    ): ForumTopicInfoModel

    /** Edits a forum topic title and optionally its custom emoji icon. */
    fun editForumTopic(
        chatId: Long,
        messageThreadId: Long,
        name: String? = null,
        editIconCustomEmoji: Boolean = false,
        customEmojiId: Long? = null,
    ): Boolean

    /** Closes a forum topic. */
    fun closeForumTopic(chatId: Long, messageThreadId: Long): Boolean

    /** Reopens a closed forum topic. */
    fun reopenForumTopic(chatId: Long, messageThreadId: Long): Boolean

    /** Enables or disables forum topics in a supergroup. Requires owner/admin privileges accepted by Telegram. */
    fun setForumTopicsEnabled(chatId: Long, enabled: Boolean = true, hasForumTabs: Boolean = true): Boolean

    // ── Channels & invite links ────────────────────────────────────────────

    /**
     * Creates a new supergroup or channel.
     *
     * @param isSupergroup if true creates a supergroup, otherwise a channel
     * @param isForum if true creates a forum-enabled supergroup
     */
    fun createSupergroupOrChannel(title: String, description: String, isSupergroup: Boolean, isForum: Boolean = false): ChatInfo

    /** Creates (or returns) an invite link for a chat (requires admin privileges). */
    fun createInviteLink(chatId: Long): ChatInviteLinkInfo

    /**
     * Lists invite links created by the current account for a chat.
     *
     * @param includeRevoked when true, returns revoked links instead of active ones
     */
    fun getChatInviteLinks(chatId: Long, includeRevoked: Boolean = false, limit: Int = 20): List<ChatInviteLinkInfo>

    /** Revokes an invite link so it can no longer be used to join the chat. */
    fun revokeChatInviteLink(chatId: Long, inviteLink: String): List<ChatInviteLinkInfo>

    /** Joins a chat via an invite link. */
    fun joinChatByInviteLink(link: String): ChatInfo

    /** Joins a public chat by username. */
    fun joinPublicChat(usernameOrLink: String): ChatInfo

    /** Lists administrators of a chat. */
    fun getChatAdmins(chatId: Long, limit: Int = 50): List<ChatMember>

    /** Lists banned users of a chat. */
    fun getBannedChatMembers(chatId: Long, limit: Int = 50): List<ChatMember>

    // ── Voice, stickers & chat photo ───────────────────────────────────────

    /** Sends a voice note (OGG/Opus) to a chat. */
    fun sendVoice(chatId: Long, filePath: String, duration: Int = 0, caption: String? = null): TelegramMessage

    /** Sends a sticker (WEBP/TGS/WEBM) to a chat. */
    fun sendSticker(chatId: Long, filePath: String, emoji: String? = null): TelegramMessage

    /** Returns the installed regular-sticker sets for the current account. */
    fun getInstalledStickerSets(limit: Int = 50): List<StickerSetInfo>

    /** Replaces the chat photo with the supplied local image. */
    fun setChatPhoto(chatId: Long, filePath: String): Boolean

    /** Removes the chat photo. */
    fun deleteChatPhoto(chatId: Long): Boolean

    // ── Drafts & profile ───────────────────────────────────────────────────

    /** Persists a draft message on the given chat. */
    fun saveDraft(chatId: Long, text: String, replyToMessageId: Long? = null): Boolean

    /** Collects all chats that currently have a non-empty draft. */
    fun getDrafts(): List<DraftInfo>

    /** Clears any persisted draft from the chat. */
    fun clearDraft(chatId: Long): Boolean

    /** Updates the current user's first name, last name and/or bio. */
    fun updateProfile(firstName: String?, lastName: String?, bio: String?): Boolean

    /** Uploads and sets the profile photo for the current user. */
    fun setProfilePhoto(filePath: String): Boolean

    /** Removes the current profile photo. If [photoId] is null the primary photo is deleted. */
    fun deleteProfilePhoto(photoId: Long? = null): Boolean

    /** Returns profile photos for the given user. */
    fun getUserProfilePhotos(userId: Long, limit: Int = 20): List<UserPhotoInfo>

    // ── Extended search, contacts & administration ────────────────────────

    /** Searches the global catalog of public chats. */
    fun searchPublicChats(query: String, limit: Int = 20): List<ChatInfo>

    /** Searches the local contact list by name. */
    fun searchContactsByQuery(query: String, limit: Int = 50): List<ContactInfo>

    /** Returns the list of blocked message senders (main block list). */
    fun getBlockedUsers(limit: Int = 100): List<UserInfo>

    /** Returns just the "last seen" string for the given user. */
    fun getUserStatus(userId: Long): String

    /** Returns the chat event log (admin actions) for a supergroup/channel. */
    fun getChatEventLog(chatId: Long, limit: Int = 50): List<AdminActionInfo>

    // ── Utilities ──────────────────────────────────────────────────────────

    /** Resolves a t.me message link to the actual [TelegramMessage]. */
    fun getMessageByLink(link: String): TelegramMessage

    /** Creates a shareable t.me link for an accessible Telegram message. */
    fun getMessageLink(
        chatId: Long,
        messageId: Long,
        forAlbum: Boolean = false,
        inMessageThread: Boolean = false,
    ): TelegramMessageLink

    /** Returns the most recent message exchanged with the given contact. */
    fun getLastInteractionWithContact(contactId: Long): TelegramMessage?

    /** Returns groups and channels the current account shares with the given user. */
    fun getGroupsInCommon(userId: Long, limit: Int = 50): List<ChatInfo>
}

