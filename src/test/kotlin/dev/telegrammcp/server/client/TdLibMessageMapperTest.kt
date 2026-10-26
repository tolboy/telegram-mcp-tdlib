package dev.telegrammcp.server.client

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.telegrammcp.server.model.TelegramMessage
import dev.telegrammcp.server.tool.ToolOutputSchemas
import dev.telegrammcp.server.tool.ToolSupport
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator
import it.tdlight.jni.TdApi
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TdLibMessageMapperTest {
    private val mapper = jacksonObjectMapper().registerModule(JavaTimeModule())

    private fun source(content: TdApi.MessageContent? = TdApi.MessageText().apply {
        text = TdApi.FormattedText("Hello", emptyArray())
    }) = TdApi.Message().apply {
        id = 100; chatId = 42; date = 1_700_000_000; senderId = TdApi.MessageSenderUser(7)
        this.content = content
    }

    private fun mapped(message: TdApi.Message) = TdLibMessageMapper.map(message, "Example", "Sender")

    @Test fun `message metadata preserves album editing outgoing state and cross-chat quoted reply`() {
        val message = source().apply {
            editDate = date + 100
            mediaAlbumId = 9_007_199_254_740_993L
            isOutgoing = true
            topicId = TdApi.MessageTopicForum(321)
            replyTo = TdApi.MessageReplyToMessage().apply {
                chatId = 99; messageId = 200
                quote = TdApi.TextQuote(TdApi.FormattedText("quoted words", emptyArray()), 4, true)
            }
        }

        val result = mapped(message)

        assertEquals(Instant.ofEpochSecond(1_700_000_100), result.editDate)
        assertEquals("9007199254740993", result.mediaAlbumId)
        val wireAlbum = mapper.readTree(mapper.writeValueAsBytes(result)).path("mediaAlbumId")
        assertTrue(wireAlbum.isTextual)
        assertEquals("9007199254740993", wireAlbum.asText())
        assertTrue(result.isOutgoing)
        assertEquals(321L, result.messageThreadId)
        assertEquals(200L, result.replyToMessageId)
        assertEquals(99L, result.replyInChatId)
        assertEquals("quoted words", result.replyQuote?.text)
        assertEquals(4, result.replyQuote?.position)
        assertEquals(true, result.replyQuote?.isManual)
        assertEquals("text", result.contentType)
    }

    @Test fun `absent optional metadata remains absent`() {
        val result = mapped(source())
        assertNull(result.editDate)
        assertNull(result.mediaAlbumId)
        assertNull(result.replyQuote)
        assertNull(result.replyInChatId)
        assertNull(result.forwardOrigin)
        assertNull(result.forwardFrom)
        assertFalse(result.isOutgoing)
        assertEquals("Hello", result.text)
        assertNull(result.mediaType)
        assertEquals(7L, result.senderId)
    }

    @Test fun `forward origins retain only the identities Telegram discloses`() {
        val cases = listOf(
            TdApi.MessageOriginUser(10) to "user",
            TdApi.MessageOriginChat(20, "Author") to "chat",
            TdApi.MessageOriginChannel(30, 40, "Channel author") to "channel",
            TdApi.MessageOriginHiddenUser("Private sender") to "hidden_user",
        )
        for ((origin, kind) in cases) {
            val result = mapped(source().apply { forwardInfo = TdApi.MessageForwardInfo(origin, date - 100, null, "") })
            val forward = requireNotNull(result.forwardOrigin)
            assertEquals(kind, forward.kind)
            assertEquals(Instant.ofEpochSecond(1_699_999_900), forward.date)
            when (origin) {
                is TdApi.MessageOriginUser -> { assertEquals(10L, forward.userId); assertEquals("User(10)", result.forwardFrom) }
                is TdApi.MessageOriginChat -> { assertEquals(20L, forward.chatId); assertEquals("Author", forward.authorSignature) }
                is TdApi.MessageOriginChannel -> { assertEquals(30L, forward.chatId); assertEquals(40L, forward.messageId) }
                is TdApi.MessageOriginHiddenUser -> {
                    assertEquals("Private sender", forward.senderName)
                    assertEquals("Private sender", result.forwardFrom)
                    assertNull(forward.userId)
                    assertNull(forward.chatId)
                    assertNull(forward.messageId)
                }
            }
        }
    }

    @Test fun `all existing topic variants retain their message thread identifiers`() {
        val topics = listOf(
            TdApi.MessageTopicThread(10), TdApi.MessageTopicForum(10),
            TdApi.MessageTopicDirectMessages(10), TdApi.MessageTopicSavedMessages(10),
        )
        topics.forEach { topic -> assertEquals(10L, mapped(source().apply { topicId = topic }).messageThreadId) }
        assertNull(mapped(source().apply { topicId = TdApi.MessageTopicForum(0) }).messageThreadId)
    }

    @Test fun `checklists preserve title tasks and completion state`() {
        val content = TdApi.MessageChecklist(TdApi.Checklist().apply {
            title = TdApi.FormattedText("Shopping", emptyArray())
            tasks = arrayOf(
                TdApi.ChecklistTask(1, TdApi.FormattedText("Milk", emptyArray()), null, 0),
                TdApi.ChecklistTask(2, TdApi.FormattedText("Bread", emptyArray()), TdApi.MessageSenderUser(7), 100),
            )
        })
        val result = mapped(source(content))
        assertEquals("[Checklist: Shopping]\n[ ] Milk\n[x] Bread", result.text)
        assertEquals("checklist", result.mediaType)
        assertEquals("checklist", result.contentType)
    }

    @Test fun `previously unsupported media has a readable content kind and summary`() {
        val venue = TdApi.MessageVenue(TdApi.Venue().apply { title = "Cafe"; address = "Main Street" })
        val cases = listOf(
            Triple(venue, "venue", "[Venue: Cafe, Main Street]"),
            Triple(TdApi.MessageDice().apply { emoji = "🎲"; value = 6 }, "dice", "[Dice: 🎲, 6]"),
            Triple(TdApi.MessageStory(20, 30, false), "story", "[Story: chat 20, id 30]"),
            Triple(TdApi.MessagePaidMedia().apply { caption = TdApi.FormattedText("Preview", emptyArray()) }, "paid_media", "Preview"),
        )
        for ((content, kind, expectedText) in cases) {
            val result = mapped(source(content))
            assertEquals(kind, result.mediaType)
            assertEquals(kind, result.contentType)
            assertEquals(expectedText, result.text)
        }
        assertEquals("[Dice: 🎲, pending]", mapped(source(TdApi.MessageDice().apply { emoji = "🎲" })).text)
    }

    @Test fun `service messages are distinguishable and unknown content stays explicit`() {
        val titleChange = mapped(source(TdApi.MessageChatChangeTitle("Project")))
        assertEquals("service", titleChange.mediaType)
        assertEquals("chat_change_title", titleChange.contentType)
        assertEquals("[Chat title changed: Project]", titleChange.text)
        val unsupported = mapped(source(TdApi.MessageUnsupported()))
        assertEquals("other", unsupported.mediaType)
        assertEquals("unsupported", unsupported.contentType)
        assertEquals("[MessageUnsupported]", unsupported.text)
        assertEquals("[Unknown]", mapped(source(null)).text)
    }

    @Test fun `poll options and original message shape survive mapper extraction`() {
        val result = mapped(source(TdApi.MessagePoll().apply {
            poll = TdApi.Poll().apply {
                question = TdApi.FormattedText("Choose?", emptyArray())
                totalVoterCount = 3
                isAnonymous = true
                options = arrayOf(TdApi.PollOption().apply {
                    text = TdApi.FormattedText("A", emptyArray()); voterCount = 3; votePercentage = 100; isChosen = true
                })
            }
        }))
        assertEquals("[Poll: Choose?]", result.text)
        assertEquals("poll", result.mediaType)
        assertEquals(3, result.poll?.totalVoterCount)
        assertEquals("A", result.poll?.options?.single()?.text)
        assertEquals(true, result.poll?.options?.single()?.isChosen)
    }

    @Test fun `old persisted messages deserialize with additive metadata defaults`() {
        val oldJson = """{"messageId":100,"chatId":42,"chatTitle":"Chat","senderName":"Sender","text":"Hello","date":"2026-10-26T00:00:00Z"}"""
        val restored = mapper.readValue<TelegramMessage>(oldJson)
        assertNull(restored.forwardOrigin)
        assertNull(restored.replyQuote)
        assertNull(restored.contentType)
        assertFalse(restored.isOutgoing)
    }

    @Test fun `rich messages satisfy explicit send schema and reflected history schema after normalization`() {
        val result = mapped(source().apply {
            isOutgoing = true; editDate = date + 100; mediaAlbumId = 500
            forwardInfo = TdApi.MessageForwardInfo(TdApi.MessageOriginHiddenUser("Hidden\u202Esender"), date - 10, null, "")
            replyTo = TdApi.MessageReplyToMessage().apply {
                chatId = 42; messageId = 50
                quote = TdApi.TextQuote(TdApi.FormattedText("Quote\u202Etext", emptyArray()), 0, true)
            }
        })
        val validator = DefaultJsonSchemaValidator()
        val serialized = mapper.convertValue(result, Map::class.java)
        val explicit = validator.validate(ToolOutputSchemas.message, serialized)
        assertTrue(explicit.valid(), explicit.errorMessage())
        for ((tool, payload) in listOf("send_message" to result, "get_history" to listOf(result))) {
            val definition = ToolSupport.definition(tool, "Test", emptyMap())
            val envelope = ToolSupport.jsonResult(payload, mapper).structuredContent()
            val validation = validator.validate(definition.outputSchema(), envelope)
            assertTrue(validation.valid(), "$tool: ${validation.errorMessage()}")
            val text = mapper.writeValueAsString(envelope)
            assertTrue(text.contains("\\u202E"))
            assertFalse(text.contains('\u202E'))
        }
    }
}
