package dev.telegrammcp.server.client

import dev.telegrammcp.server.model.AlbumItem
import dev.telegrammcp.server.model.AlbumItemType
import dev.telegrammcp.server.model.ChatActionType
import dev.telegrammcp.server.model.ContactSpec
import dev.telegrammcp.server.model.VenueSpec
import it.tdlight.jni.TdApi
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** TDLib 1.8.68 request shapes for albums, rich sends, chat actions and moderation. */
class TdLibMediaModerationBuildersTest {

    @Test
    fun `album request wraps each item in its input type and keeps order`() {
        val request = TdLibRequestBuilders.albumRequest(
            42L,
            listOf(
                AlbumItem("/tmp/a.jpg", AlbumItemType.PHOTO, "first"),
                AlbumItem("/tmp/b.mp4", AlbumItemType.VIDEO),
            ),
        )

        assertEquals(42L, request.chatId)
        assertEquals(2, request.inputMessageContents.size)
        val photo = assertIs<TdApi.InputMessagePhoto>(request.inputMessageContents[0])
        assertEquals("/tmp/a.jpg", assertIs<TdApi.InputFileLocal>(photo.photo.photo).path)
        assertEquals("first", photo.caption.text)
        val video = assertIs<TdApi.InputMessageVideo>(request.inputMessageContents[1])
        assertEquals("/tmp/b.mp4", assertIs<TdApi.InputFileLocal>(video.video.video).path)
        assertNull(video.caption)
    }

    @Test
    fun `document and audio album items use their own input types`() {
        val document = assertIs<TdApi.InputMessageDocument>(
            TdLibRequestBuilders.albumItemContent(AlbumItem("/tmp/a.pdf", AlbumItemType.DOCUMENT, "doc")),
        )
        assertEquals("/tmp/a.pdf", assertIs<TdApi.InputFileLocal>(document.document.document).path)
        assertEquals("doc", document.caption.text)
        val audio = assertIs<TdApi.InputMessageAudio>(
            TdLibRequestBuilders.albumItemContent(AlbumItem("/tmp/a.mp3", AlbumItemType.AUDIO)),
        )
        assertEquals("/tmp/a.mp3", assertIs<TdApi.InputFileLocal>(audio.audio.audio).path)
    }

    @Test
    fun `every chat action type maps to a TDLib action`() {
        assertIs<TdApi.ChatActionTyping>(TdLibRequestBuilders.chatAction(ChatActionType.TYPING))
        assertIs<TdApi.ChatActionUploadingPhoto>(TdLibRequestBuilders.chatAction(ChatActionType.UPLOADING_PHOTO))
        assertIs<TdApi.ChatActionCancel>(TdLibRequestBuilders.chatAction(ChatActionType.CANCEL))
        assertEquals(
            ChatActionType.entries.size,
            ChatActionType.entries.map { TdLibRequestBuilders.chatAction(it).javaClass }.toSet().size,
            "each action type maps to a distinct TDLib class",
        )
    }

    @Test
    fun `location venue and contact contents carry their fields`() {
        val location = TdLibRequestBuilders.locationContent(55.75, 37.62, 25.0)
        assertEquals(55.75, location.location.latitude)
        assertEquals(37.62, location.location.longitude)
        assertEquals(25.0, location.location.horizontalAccuracy)

        val venue = TdLibRequestBuilders.venueContent(
            VenueSpec(1.5, 2.5, "Cafe", "Main St 1", "foursquare", "id-1", "food/cafe"),
        ).venue
        assertEquals(1.5, venue.location.latitude)
        assertEquals("Cafe", venue.title)
        assertEquals("Main St 1", venue.address)
        assertEquals("foursquare", venue.provider)
        assertEquals("id-1", venue.id)
        assertEquals("food/cafe", venue.type)

        val contact = TdLibRequestBuilders.contactContent(ContactSpec("+100", "Ann", "Lee", "", 7L)).contact
        assertEquals("+100", contact.phoneNumber)
        assertEquals("Ann", contact.firstName)
        assertEquals("Lee", contact.lastName)
        assertEquals(7L, contact.userId)
    }

    @Test
    fun `message sender sign selects user or chat`() {
        assertEquals(5L, assertIs<TdApi.MessageSenderUser>(TdLibRequestBuilders.messageSender(5L)).userId)
        assertEquals(-1001L, assertIs<TdApi.MessageSenderChat>(TdLibRequestBuilders.messageSender(-1001L)).chatId)
    }

    private fun stat(value: Double, previous: Double, growth: Double) = TdApi.StatisticalValue().apply {
        this.value = value
        this.previousValue = previous
        this.growthRatePercentage = growth
    }

    private fun range(start: Int, end: Int) = TdApi.DateRange().apply { startDate = start; endDate = end }

    @Test
    fun `supergroup statistics keep headline values and top lists`() {
        val stats = TdApi.ChatStatisticsSupergroup().apply {
            period = range(1_700_000_000, 1_700_600_000)
            memberCount = stat(120.0, 100.0, 20.0)
            messageCount = stat(50.0, 40.0, 25.0)
            viewerCount = stat(80.0, 80.0, 0.0)
            senderCount = stat(10.0, 12.0, -16.7)
            topSenders = arrayOf(TdApi.ChatStatisticsMessageSenderInfo().apply {
                userId = 9L; sentMessageCount = 30; averageCharacterCount = 42
            })
            topAdministrators = arrayOf(TdApi.ChatStatisticsAdministratorActionsInfo().apply {
                userId = 3L; deletedMessageCount = 4; bannedUserCount = 1; restrictedUserCount = 2
            })
            topInviters = arrayOf(TdApi.ChatStatisticsInviterInfo().apply { userId = 8L; addedMemberCount = 6 })
        }

        val summary = TdLibRequestBuilders.chatStatistics(-100L, stats)

        assertEquals("supergroup", summary.type)
        assertEquals(-100L, summary.chatId)
        assertEquals(Instant.ofEpochSecond(1_700_000_000), summary.periodStart)
        assertEquals(Instant.ofEpochSecond(1_700_600_000), summary.periodEnd)
        assertEquals(listOf("member_count", "message_count", "viewer_count", "sender_count"), summary.values.map { it.name })
        assertEquals(120.0, summary.values.first().value)
        assertEquals(20.0, summary.values.first().growthRatePercentage)
        assertEquals(9L, summary.topSenders.single().userId)
        assertEquals(30, summary.topSenders.single().sentMessageCount)
        assertEquals(1, summary.topAdministrators.single().bannedUserCount)
        assertEquals(6, summary.topInviters.single().addedMemberCount)
        assertNull(summary.enabledNotificationsPercentage)
    }

    @Test
    fun `channel statistics keep mean counts and notification share`() {
        val stats = TdApi.ChatStatisticsChannel().apply {
            period = range(10, 20)
            memberCount = stat(1000.0, 900.0, 11.1)
            meanMessageViewCount = stat(300.0, 250.0, 20.0)
            meanMessageShareCount = stat(5.0, 5.0, 0.0)
            meanMessageReactionCount = stat(12.0, 10.0, 20.0)
            meanStoryViewCount = stat(0.0, 0.0, 0.0)
            meanStoryShareCount = stat(0.0, 0.0, 0.0)
            meanStoryReactionCount = stat(0.0, 0.0, 0.0)
            enabledNotificationsPercentage = 37.5
        }

        val summary = TdLibRequestBuilders.chatStatistics(-200L, stats)

        assertEquals("channel", summary.type)
        assertEquals(7, summary.values.size)
        assertEquals("mean_message_view_count", summary.values[1].name)
        assertEquals(300.0, summary.values[1].value)
        assertEquals(37.5, summary.enabledNotificationsPercentage)
        assertEquals(emptyList(), summary.topSenders)
    }
}
