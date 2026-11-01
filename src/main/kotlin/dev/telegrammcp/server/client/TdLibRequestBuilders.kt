package dev.telegrammcp.server.client

import dev.telegrammcp.server.model.AlbumItem
import dev.telegrammcp.server.model.AlbumItemType
import dev.telegrammcp.server.model.BotCommand
import dev.telegrammcp.server.model.ChatActionType
import dev.telegrammcp.server.model.ChatAdministratorRights
import dev.telegrammcp.server.model.ChatStatisticValue
import dev.telegrammcp.server.model.ChatStatisticsSummary
import dev.telegrammcp.server.model.ChatStatisticsTopAdministrator
import dev.telegrammcp.server.model.ChatStatisticsTopInviter
import dev.telegrammcp.server.model.ChatStatisticsTopSender
import dev.telegrammcp.server.model.ContactSpec
import dev.telegrammcp.server.model.VenueSpec
import it.tdlight.jni.TdApi
import java.time.Instant

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

    private fun caption(text: String?): TdApi.FormattedText? =
        text?.takeIf { it.isNotEmpty() }?.let { TdApi.FormattedText(it, emptyArray()) }

    /** One album item; TDLib 1.8.68 wraps each file in an `input*` object. */
    fun albumItemContent(item: AlbumItem): TdApi.InputMessageContent {
        val file = TdApi.InputFileLocal(item.filePath)
        return when (item.type) {
            AlbumItemType.PHOTO -> TdApi.InputMessagePhoto().apply {
                photo = TdApi.InputPhoto(file, null, null, IntArray(0), 0, 0)
                caption = caption(item.caption)
            }
            AlbumItemType.VIDEO -> TdApi.InputMessageVideo().apply {
                video = TdApi.InputVideo(file, null, null, 0, IntArray(0), 0, 0, 0, true)
                caption = caption(item.caption)
            }
            AlbumItemType.DOCUMENT -> TdApi.InputMessageDocument().apply {
                document = TdApi.InputDocument(file, null, false)
                caption = caption(item.caption)
            }
            AlbumItemType.AUDIO -> TdApi.InputMessageAudio().apply {
                audio = TdApi.InputAudio(file, null, 0, "", "")
                caption = caption(item.caption)
            }
        }
    }

    fun albumRequest(chatId: Long, items: List<AlbumItem>): TdApi.SendMessageAlbum = TdApi.SendMessageAlbum().apply {
        this.chatId = chatId
        this.inputMessageContents = items.map(::albumItemContent).toTypedArray()
    }

    fun chatAction(type: ChatActionType): TdApi.ChatAction = when (type) {
        ChatActionType.TYPING -> TdApi.ChatActionTyping()
        ChatActionType.RECORDING_VOICE_NOTE -> TdApi.ChatActionRecordingVoiceNote()
        ChatActionType.UPLOADING_PHOTO -> TdApi.ChatActionUploadingPhoto(0)
        ChatActionType.UPLOADING_VIDEO -> TdApi.ChatActionUploadingVideo(0)
        ChatActionType.UPLOADING_DOCUMENT -> TdApi.ChatActionUploadingDocument(0)
        ChatActionType.CHOOSING_STICKER -> TdApi.ChatActionChoosingSticker()
        ChatActionType.CHOOSING_LOCATION -> TdApi.ChatActionChoosingLocation()
        ChatActionType.CHOOSING_CONTACT -> TdApi.ChatActionChoosingContact()
        ChatActionType.CANCEL -> TdApi.ChatActionCancel()
    }

    fun locationContent(latitude: Double, longitude: Double, horizontalAccuracy: Double): TdApi.InputMessageLocation =
        TdApi.InputMessageLocation().apply { location = TdApi.Location(latitude, longitude, horizontalAccuracy) }

    fun venueContent(venue: VenueSpec): TdApi.InputMessageVenue = TdApi.InputMessageVenue(
        TdApi.Venue(
            TdApi.Location(venue.latitude, venue.longitude, 0.0),
            venue.title, venue.address, venue.provider, venue.providerVenueId, venue.providerVenueType,
        ),
    )

    fun contactContent(contact: ContactSpec): TdApi.InputMessageContact = TdApi.InputMessageContact(
        TdApi.Contact(contact.phoneNumber, contact.firstName, contact.lastName, contact.vcard, contact.userId),
    )

    /** Telegram user IDs are positive; chats that post as themselves (channels, groups) have negative IDs. */
    fun messageSender(senderId: Long): TdApi.MessageSender =
        if (senderId < 0) TdApi.MessageSenderChat(senderId) else TdApi.MessageSenderUser(senderId)

    private fun value(name: String, value: TdApi.StatisticalValue?): ChatStatisticValue? =
        value?.let { ChatStatisticValue(name, it.value, it.previousValue, it.growthRatePercentage) }

    private fun period(range: TdApi.DateRange?): Pair<Instant, Instant> =
        Instant.ofEpochSecond((range?.startDate ?: 0).toLong()) to Instant.ofEpochSecond((range?.endDate ?: 0).toLong())

    /** Headline values and top lists only; graphs load asynchronously and are left out. */
    fun chatStatistics(chatId: Long, statistics: TdApi.ChatStatistics): ChatStatisticsSummary = when (statistics) {
        is TdApi.ChatStatisticsSupergroup -> {
            val (start, end) = period(statistics.period)
            ChatStatisticsSummary(
                chatId = chatId,
                type = "supergroup",
                periodStart = start,
                periodEnd = end,
                values = listOfNotNull(
                    value("member_count", statistics.memberCount),
                    value("message_count", statistics.messageCount),
                    value("viewer_count", statistics.viewerCount),
                    value("sender_count", statistics.senderCount),
                ),
                topSenders = statistics.topSenders.orEmpty().map {
                    ChatStatisticsTopSender(it.userId, it.sentMessageCount, it.averageCharacterCount)
                },
                topAdministrators = statistics.topAdministrators.orEmpty().map {
                    ChatStatisticsTopAdministrator(it.userId, it.deletedMessageCount, it.bannedUserCount, it.restrictedUserCount)
                },
                topInviters = statistics.topInviters.orEmpty().map { ChatStatisticsTopInviter(it.userId, it.addedMemberCount) },
            )
        }
        is TdApi.ChatStatisticsChannel -> {
            val (start, end) = period(statistics.period)
            ChatStatisticsSummary(
                chatId = chatId,
                type = "channel",
                periodStart = start,
                periodEnd = end,
                values = listOfNotNull(
                    value("member_count", statistics.memberCount),
                    value("mean_message_view_count", statistics.meanMessageViewCount),
                    value("mean_message_share_count", statistics.meanMessageShareCount),
                    value("mean_message_reaction_count", statistics.meanMessageReactionCount),
                    value("mean_story_view_count", statistics.meanStoryViewCount),
                    value("mean_story_share_count", statistics.meanStoryShareCount),
                    value("mean_story_reaction_count", statistics.meanStoryReactionCount),
                ),
                enabledNotificationsPercentage = statistics.enabledNotificationsPercentage,
            )
        }
    }
}
