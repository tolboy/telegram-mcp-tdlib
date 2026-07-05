package dev.telegrammcp.server.tool.message

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.model.ParseMode
import dev.telegrammcp.server.model.ReplyMarkupSpec
import dev.telegrammcp.server.model.TelegramMessage
import dev.telegrammcp.server.service.EntityResolverService
import dev.telegrammcp.server.service.GuardrailService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SendMessageToolTest {

    private lateinit var telegramClient: TelegramClientService
    private lateinit var entityResolver: EntityResolverService
    private lateinit var guardrailService: GuardrailService
    private lateinit var objectMapper: ObjectMapper
    private lateinit var tool: SendMessageTool
    private lateinit var exchange: McpSyncServerExchange

    @BeforeEach
    fun setUp() {
        telegramClient = mockk()
        entityResolver = mockk()
        guardrailService = mockk(relaxed = true)
        objectMapper = jacksonObjectMapper().findAndRegisterModules()
        exchange = mockk(relaxed = true)

        tool = SendMessageTool(
            telegramClient = telegramClient,
            entityResolver = entityResolver,
            guardrailService = guardrailService,
            objectMapper = objectMapper,
            meterRegistry = SimpleMeterRegistry(),
        )
    }

    @Test
    fun `sends message with plain text`() {
        val sentMsg = TelegramMessage(
            messageId = 100, chatId = 42, chatTitle = "Test",
            senderName = "Bot", text = "Hello", date = Instant.now(),
        )
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { telegramClient.sendMessage(42L, "Hello", ParseMode.PLAIN, null, null) } returns sentMsg

        val result = tool.execute(exchange, mapOf("chat_id" to 42, "text" to "Hello"))

        assertFalse(result.isError)
        verify { guardrailService.validateInput("Hello") }
        verify { guardrailService.validateChatAccess(42L) }
    }

    @Test
    fun `sends message with HTML parse mode`() {
        val sentMsg = TelegramMessage(
            messageId = 101, chatId = 42, chatTitle = "Test",
            senderName = "Bot", text = "<b>Bold</b>", date = Instant.now(),
        )
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { telegramClient.sendMessage(42L, "<b>Bold</b>", ParseMode.HTML, null, null) } returns sentMsg

        val result = tool.execute(
            exchange,
            mapOf("chat_id" to 42, "text" to "<b>Bold</b>", "parse_mode" to "html"),
        )

        assertFalse(result.isError)
    }

    @Test
    fun `sends message with reply keyboard`() {
        val sentMsg = TelegramMessage(
            messageId = 102, chatId = 42, chatTitle = "Test",
            senderName = "Bot", text = "Choose", date = Instant.now(),
        )
        val replyMarkup = ReplyMarkupSpec(
            type = ReplyMarkupSpec.Kind.SHOW_KEYBOARD,
            rows = listOf(listOf("Claude 4 Sonnet", "Grok 4"), listOf("Cancel")),
            oneTime = true,
            resize = true,
            placeholder = "Pick a model",
        )
        every { entityResolver.resolve(42 as Any) } returns 42L
        every {
            telegramClient.sendMessage(42L, "Choose", ParseMode.PLAIN, replyMarkup, null)
        } returns sentMsg

        val result = tool.execute(
            exchange,
            mapOf(
                "chat_id" to 42,
                "text" to "Choose",
                "reply_markup" to mapOf(
                    "type" to "show_keyboard",
                    "rows" to listOf(
                        listOf("Claude 4 Sonnet", "Grok 4"),
                        listOf("Cancel"),
                    ),
                    "one_time" to true,
                    "resize" to true,
                    "placeholder" to "Pick a model",
                ),
            ),
        )

        assertFalse(result.isError)
    }

    @Test
    fun `sends message to forum topic thread`() {
        val sentMsg = TelegramMessage(
            messageId = 103, chatId = 42, chatTitle = "Forum",
            senderName = "Bot", text = "Threaded", date = Instant.now(),
            messageThreadId = 9001L,
        )
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { telegramClient.sendMessage(42L, "Threaded", ParseMode.PLAIN, null, 9001L) } returns sentMsg

        val result = tool.execute(
            exchange,
            mapOf("chat_id" to 42, "text" to "Threaded", "message_thread_id" to 9001),
        )

        assertFalse(result.isError)
        verify { telegramClient.sendMessage(42L, "Threaded", ParseMode.PLAIN, null, 9001L) }
    }

    @Test
    fun `returns error when text is missing`() {
        every { entityResolver.resolve(42 as Any) } returns 42L

        val result = tool.execute(exchange, mapOf("chat_id" to 42))

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("text is required"))
    }

    @Test
    fun `returns error when text is blank`() {
        every { entityResolver.resolve(42 as Any) } returns 42L

        val result = tool.execute(exchange, mapOf("chat_id" to 42, "text" to "  "))

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("text must not be blank"))
    }

    @Test
    fun `returns error for invalid parse_mode`() {
        every { entityResolver.resolve(42 as Any) } returns 42L

        val result = tool.execute(
            exchange,
            mapOf("chat_id" to 42, "text" to "test", "parse_mode" to "xml"),
        )

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("parse_mode"))
    }

    @Test
    fun `returns error for reply keyboard without rows`() {
        every { entityResolver.resolve(42 as Any) } returns 42L

        val result = tool.execute(
            exchange,
            mapOf(
                "chat_id" to 42,
                "text" to "Choose",
                "reply_markup" to mapOf("type" to "show_keyboard"),
            ),
        )

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("reply_markup.rows is required"))
    }
}


