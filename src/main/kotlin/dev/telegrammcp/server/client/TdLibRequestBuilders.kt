package dev.telegrammcp.server.client

import dev.telegrammcp.server.model.BotCommand
import dev.telegrammcp.server.model.ChatAdministratorRights
import it.tdlight.jni.TdApi

/**
 * Builders for TDLib request objects whose shape changed between TDLib
 * releases. Keeping them here makes the TL-schema mapping unit-testable
 * without a live TDLib client.
 */
internal object TdLibRequestBuilders {

    /** TDLib 1.8.68 added `is_ephemeral`; user-managed commands are never ephemeral. */
    fun botCommand(command: BotCommand): TdApi.BotCommand =
        TdApi.BotCommand(command.command, command.description, false)

    /** TDLib 1.8.68 moved the file, duration and waveform into `inputVoiceNote`. */
    fun voiceNoteContent(filePath: String, duration: Int, caption: String?): TdApi.InputMessageVoiceNote =
        TdApi.InputMessageVoiceNote().apply {
            this.voiceNote = TdApi.InputVoiceNote(TdApi.InputFileLocal(filePath), duration.coerceAtLeast(0), ByteArray(0))
            this.caption = caption?.let { TdApi.FormattedText(it, emptyArray()) }
        }

    /** TDLib 1.8.68 moved the file, thumbnail and dimensions into `inputSticker`. */
    fun stickerContent(filePath: String, emoji: String?): TdApi.InputMessageSticker =
        TdApi.InputMessageSticker().apply {
            this.sticker = TdApi.InputSticker(TdApi.InputFileLocal(filePath), null, 0, 0)
            this.emoji = emoji ?: ""
        }

    fun administratorRights(rights: ChatAdministratorRights): TdApi.ChatAdministratorRights = TdApi.ChatAdministratorRights().apply {
        canManageChat = rights.canManageChat
        canChangeInfo = rights.canChangeInfo
        canPostMessages = rights.canPostMessages
        canEditMessages = rights.canEditMessages
        canDeleteMessages = rights.canDeleteMessages
        canInviteUsers = rights.canInviteUsers
        canRestrictMembers = rights.canRestrictMembers
        canPinMessages = rights.canPinMessages
        canManageTopics = rights.canManageTopics
        canPromoteMembers = rights.canPromoteMembers
        canManageVideoChats = rights.canManageVideoChats
        canPostStories = rights.canPostStories
        canEditStories = rights.canEditStories
        canDeleteStories = rights.canDeleteStories
        canManageDirectMessages = rights.canManageDirectMessages
        canManageTags = rights.canManageTags
        canSendWelcomeMessages = rights.canSendWelcomeMessages
        isAnonymous = rights.isAnonymous
    }

    fun administratorRights(rights: TdApi.ChatAdministratorRights): ChatAdministratorRights = ChatAdministratorRights(
        canManageChat = rights.canManageChat,
        canChangeInfo = rights.canChangeInfo,
        canPostMessages = rights.canPostMessages,
        canEditMessages = rights.canEditMessages,
        canDeleteMessages = rights.canDeleteMessages,
        canInviteUsers = rights.canInviteUsers,
        canRestrictMembers = rights.canRestrictMembers,
        canPinMessages = rights.canPinMessages,
        canManageTopics = rights.canManageTopics,
        canPromoteMembers = rights.canPromoteMembers,
        canManageVideoChats = rights.canManageVideoChats,
        canPostStories = rights.canPostStories,
        canEditStories = rights.canEditStories,
        canDeleteStories = rights.canDeleteStories,
        canManageDirectMessages = rights.canManageDirectMessages,
        canManageTags = rights.canManageTags,
        canSendWelcomeMessages = rights.canSendWelcomeMessages,
        isAnonymous = rights.isAnonymous,
    )
}
