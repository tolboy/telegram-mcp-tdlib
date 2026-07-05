package dev.telegrammcp.server.model

import java.time.Instant

/**
 * Describes a reply-markup that can be attached to an outgoing message.
 *
 * - `SHOW_KEYBOARD` — renders a custom reply keyboard with the provided rows.
 *   Each button sends its text as a regular message when tapped.
 * - `REMOVE` — removes any previously shown reply keyboard.
 */
data class ReplyMarkupSpec(
    val type: Kind,
    val rows: List<List<String>> = emptyList(),
    val oneTime: Boolean = true,
    val resize: Boolean = true,
    val placeholder: String? = null,
) {
    enum class Kind { SHOW_KEYBOARD, REMOVE }
}

/**
 * Domain representation of a Telegram message, decoupled from the TDLib wire format.
 */
data class TelegramMessage(
    val messageId: Long,
    val chatId: Long,
    val chatTitle: String?,
    val senderName: String,
    val senderId: Long? = null,
    val text: String?,
    val date: Instant,
    val replyToMessageId: Long? = null,
    val messageThreadId: Long? = null,
    val mediaType: String? = null,
    val forwardFrom: String? = null,
    val poll: TelegramPoll? = null,
)

/** Shareable Telegram link generated for a specific message. */
data class TelegramMessageLink(
    val chatId: Long,
    val messageId: Long,
    val link: String,
    val isPublic: Boolean,
)

/** Poll snapshot attached to a Telegram message. */
data class TelegramPoll(
    val question: String,
    val totalVoterCount: Int,
    val isAnonymous: Boolean,
    val isClosed: Boolean,
    val options: List<TelegramPollOption>,
)

/** Single poll option with the current user's vote state. */
data class TelegramPollOption(
    val text: String,
    val voterCount: Int,
    val votePercentage: Int,
    val isChosen: Boolean,
)

/**
 * Wrapper carrying the result of a channel/group search.
 */
@Suppress("unused")
data class SearchResult(
    val query: String,
    val chatId: Long,
    val chatTitle: String?,
    val matchedMessages: List<TelegramMessage>,
    val totalMatches: Int,
)

/** Identifies a Telegram chat that the client has access to. */
data class ChatInfo(
    val chatId: Long,
    val title: String?,
    val type: ChatType,
    val memberCount: Int? = null,
    val description: String? = null,
    val username: String? = null,
    val unreadCount: Int? = null,
    val lastMessageDate: Instant? = null,
    val isMuted: Boolean = false,
    /**
     * Whether this chat has a profile photo set. Lets callers (e.g. the
     * host-side chat reconciler) skip a redundant edit_chat_photo call
     * after a recovery when Telegram has already attached the avatar.
     */
    val hasPhoto: Boolean = false,
)

/** Supported Telegram chat types. */
enum class ChatType { PRIVATE, GROUP, SUPERGROUP, CHANNEL, SECRET }

/** Represents a member of a Telegram chat. */
data class ChatMember(
    val userId: Long,
    val firstName: String,
    val lastName: String? = null,
    val username: String? = null,
    val status: MemberStatus,
)

/** Membership status within a chat. */
enum class MemberStatus { CREATOR, ADMIN, MEMBER, RESTRICTED, LEFT, BANNED }

/** A Telegram account privacy setting supported by the focused P3 controls. */
enum class PrivacySetting {
    SHOW_STATUS,
    SHOW_PROFILE_PHOTO,
    SHOW_PHONE_NUMBER,
    SHOW_LINK_IN_FORWARDED_MESSAGES,
    ALLOW_CHAT_INVITES,
    ALLOW_CALLS,
    ALLOW_PEER_TO_PEER_CALLS,
    ALLOW_PRIVATE_VOICE_AND_VIDEO_NOTES,
}

/** One rule in a Telegram account privacy policy. */
data class PrivacyRule(
    val kind: PrivacyRuleKind,
    val userIds: List<Long> = emptyList(),
    val chatIds: List<Long> = emptyList(),
)

enum class PrivacyRuleKind {
    ALLOW_ALL,
    RESTRICT_ALL,
    ALLOW_CONTACTS,
    RESTRICT_CONTACTS,
    ALLOW_USERS,
    RESTRICT_USERS,
    ALLOW_CHAT_MEMBERS,
    RESTRICT_CHAT_MEMBERS,
    ALLOW_BOTS,
    RESTRICT_BOTS,
    ALLOW_PREMIUM_USERS,
}

/** The complete, ordered rule set for one account privacy setting. */
data class PrivacySettingRules(
    val setting: PrivacySetting,
    val rules: List<PrivacyRule>,
)

/** Target audience for a bot's command menu. */
data class BotCommandScope(
    val kind: BotCommandScopeKind,
    val chatId: Long? = null,
    val userId: Long? = null,
)

enum class BotCommandScopeKind {
    DEFAULT,
    ALL_PRIVATE_CHATS,
    ALL_GROUP_CHATS,
    ALL_CHAT_ADMINISTRATORS,
    CHAT,
    CHAT_ADMINISTRATORS,
    CHAT_MEMBER,
}

/** A bot command and its Telegram-visible description. */
data class BotCommand(
    val command: String,
    val description: String,
)

/** Default or member-specific permissions for a Telegram group. */
data class ChatPermissions(
    val canSendBasicMessages: Boolean = false,
    val canSendAudios: Boolean = false,
    val canSendDocuments: Boolean = false,
    val canSendPhotos: Boolean = false,
    val canSendVideos: Boolean = false,
    val canSendVideoNotes: Boolean = false,
    val canSendVoiceNotes: Boolean = false,
    val canSendPolls: Boolean = false,
    val canSendOtherMessages: Boolean = false,
    val canAddLinkPreviews: Boolean = false,
    val canReactToMessages: Boolean = false,
    val canEditTag: Boolean = false,
    val canChangeInfo: Boolean = false,
    val canInviteUsers: Boolean = false,
    val canPinMessages: Boolean = false,
    val canCreateTopics: Boolean = false,
)

/** Detailed administrator privileges for one group member. */
data class ChatAdministratorRights(
    val canManageChat: Boolean = false,
    val canChangeInfo: Boolean = false,
    val canPostMessages: Boolean = false,
    val canEditMessages: Boolean = false,
    val canDeleteMessages: Boolean = false,
    val canInviteUsers: Boolean = false,
    val canRestrictMembers: Boolean = false,
    val canPinMessages: Boolean = false,
    val canManageTopics: Boolean = false,
    val canPromoteMembers: Boolean = false,
    val canManageVideoChats: Boolean = false,
    val canPostStories: Boolean = false,
    val canEditStories: Boolean = false,
    val canDeleteStories: Boolean = false,
    val canManageDirectMessages: Boolean = false,
    val canManageTags: Boolean = false,
    val isAnonymous: Boolean = false,
)

/** Text parse mode for outgoing messages. */
enum class ParseMode { PLAIN, HTML, MARKDOWN }

/** Aggregated reactions for a single message. */
data class MessageReactionSummary(
    val chatId: Long,
    val messageId: Long,
    val reactions: List<ReactionInfo>,
)

/** A single reaction entry with the sender who placed it. */
data class ReactionInfo(
    val emoji: String,
    val senderId: Long? = null,
    val senderName: String? = null,
    val isOutgoing: Boolean = false,
    val date: Instant? = null,
)

/** Information about a Telegram forum topic. */
data class ForumTopicInfoModel(
    val chatId: Long,
    val topicId: Long,
    val messageThreadId: Long,
    val name: String,
    val isGeneral: Boolean,
    val isClosed: Boolean,
    val isPinned: Boolean,
    val unreadCount: Int,
    val creationDate: Instant?,
    val iconColor: Int? = null,
    val customEmojiId: Long? = null,
)

/** Single inline-keyboard button. */
data class InlineButtonInfo(
    val row: Int,
    val index: Int,
    val text: String,
    val type: String,
    val url: String? = null,
    val callbackData: String? = null,
)

/** Chat invite link details. */
data class ChatInviteLinkInfo(
    val chatId: Long,
    val inviteLink: String,
    val name: String? = null,
    val creatorUserId: Long,
    val isPrimary: Boolean,
    val isRevoked: Boolean,
    val memberLimit: Int,
    val memberCount: Int,
    val expireDate: Instant? = null,
)

/** A user who viewed a message, as reported by Telegram read receipts. */
data class MessageViewerInfo(
    val userId: Long,
    val userName: String? = null,
    val viewDate: Instant? = null,
)

/** Sticker set summary returned by get_sticker_sets. */
data class StickerSetInfo(
    val id: Long,
    val title: String,
    val name: String,
    val size: Int,
    val isOfficial: Boolean,
    val isArchived: Boolean,
    val isOwned: Boolean,
)

/** Persisted draft message on a chat. */
data class DraftInfo(
    val chatId: Long,
    val chatTitle: String?,
    val text: String,
    val replyToMessageId: Long? = null,
    val date: Instant? = null,
)

/** Compact folder entry from TDLib's account-level folder update stream. */
data class ChatFolderInfo(
    val id: Int,
    val title: String,
    val iconName: String? = null,
    val colorId: Int = 0,
    val isShareable: Boolean = false,
    val hasMyInviteLinks: Boolean = false,
)

/** The complete rule set used to create or replace a Telegram chat folder. */
data class ChatFolderDefinition(
    val title: String,
    val iconName: String? = null,
    val colorId: Int = 0,
    val isShareable: Boolean = false,
    val pinnedChatIds: List<Long> = emptyList(),
    val includedChatIds: List<Long> = emptyList(),
    val excludedChatIds: List<Long> = emptyList(),
    val excludeMuted: Boolean = false,
    val excludeRead: Boolean = false,
    val excludeArchived: Boolean = false,
    val includeContacts: Boolean = false,
    val includeNonContacts: Boolean = false,
    val includeBots: Boolean = false,
    val includeGroups: Boolean = false,
    val includeChannels: Boolean = false,
)

/** Full definition of one Telegram chat folder. */
data class ChatFolderDetails(
    val id: Int,
    val definition: ChatFolderDefinition,
)

/** Current account-level folder state; TDLib supplies it through an update. */
data class ChatFolderListing(
    val folders: List<ChatFolderInfo>,
    val mainChatListPosition: Int = 0,
    val tagsEnabled: Boolean = false,
    val initialized: Boolean = false,
)

/** A queued Telegram message and its scheduling mode. */
data class ScheduledMessage(
    val message: TelegramMessage,
    val sendAt: Instant? = null,
    val repeatPeriodSeconds: Int = 0,
    val sendWhenOnline: Boolean = false,
)

/** Single profile photo belonging to a Telegram user. */
data class UserPhotoInfo(
    val photoId: Long,
    val addedDate: Instant?,
    val hasAnimation: Boolean,
    val width: Int? = null,
    val height: Int? = null,
    val fileSize: Long? = null,
)

/** Chat event log entry (admin actions / auditing). */
data class AdminActionInfo(
    val eventId: Long,
    val date: Instant,
    val actorUserId: Long?,
    val actorName: String?,
    val action: String,
    val details: String? = null,
)
