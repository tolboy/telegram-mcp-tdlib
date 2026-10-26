package dev.telegrammcp.server.client

import it.tdlight.jni.TdApi

/**
 * Explicit allowlist of the TDLib functions this server sends that only read
 * state and are therefore safe to retry unchanged after a transient failure.
 *
 * Anything not listed is treated as a write: a new TDLib function must be
 * added here deliberately rather than inferred from its name. Functions that
 * look like reads but act on the user's behalf or consume a quota are
 * deliberately absent, for example:
 * - `GetCallbackQueryAnswer` presses a bot button;
 * - `RecognizeSpeech` starts server-side transcription and counts against the
 *   account's speech-recognition quota.
 *
 * The list also decides flood-gate scoping (see [TelegramFloodGate.scopeOf]):
 * reads are gated per method across the account, writes per method and chat.
 */
internal object TelegramReadRequests {

    private val SAFE_READS: Set<Class<out TdApi.Function<*>>> = setOf(
        TdApi.DownloadFile::class.java,
        TdApi.GetBasicGroupFullInfo::class.java,
        TdApi.GetBlockedMessageSenders::class.java,
        TdApi.GetChat::class.java,
        TdApi.GetChatEventLog::class.java,
        TdApi.GetChatFolder::class.java,
        TdApi.GetChatHistory::class.java,
        TdApi.GetChatInviteLinks::class.java,
        TdApi.GetChatScheduledMessages::class.java,
        TdApi.GetChats::class.java,
        TdApi.GetCommands::class.java,
        TdApi.GetContacts::class.java,
        TdApi.GetForumTopics::class.java,
        TdApi.GetGroupsInCommon::class.java,
        TdApi.GetInstalledStickerSets::class.java,
        TdApi.GetMe::class.java,
        TdApi.GetMessage::class.java,
        TdApi.GetMessageAddedReactions::class.java,
        TdApi.GetMessageLink::class.java,
        TdApi.GetMessageLinkInfo::class.java,
        TdApi.GetMessageViewers::class.java,
        TdApi.GetMessages::class.java,
        TdApi.GetSupergroup::class.java,
        TdApi.GetSupergroupFullInfo::class.java,
        TdApi.GetUser::class.java,
        TdApi.GetUserFullInfo::class.java,
        TdApi.GetUserPrivacySettingRules::class.java,
        TdApi.GetUserProfilePhotos::class.java,
        TdApi.LoadChats::class.java,
        TdApi.ParseTextEntities::class.java,
        TdApi.SearchChatMembers::class.java,
        TdApi.SearchChatMessages::class.java,
        TdApi.SearchContacts::class.java,
        TdApi.SearchMessages::class.java,
        TdApi.SearchPublicChat::class.java,
        TdApi.SearchPublicChats::class.java,
        TdApi.SearchUserByPhoneNumber::class.java,
    )

    /** True when [request] is on the allowlist of side-effect-free reads. */
    fun isSafeRead(request: TdApi.Function<*>): Boolean = request.javaClass in SAFE_READS

    /** The allowlist, for tests and documentation. */
    val names: Set<String> get() = SAFE_READS.mapTo(sortedSetOf()) { it.simpleName }
}
