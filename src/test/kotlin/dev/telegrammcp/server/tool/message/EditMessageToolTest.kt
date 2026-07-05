package dev.telegrammcp.server.tool.message

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.model.ParseMode
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EditMessageToolTest {

    private lateinit var telegramClient: TelegramClientService
    private lateinit var entityResolver: EntityResolverService
    private lateinit var guardrailService: GuardrailService
    private lateinit var objectMapper: ObjectMapper
    private lateinit var tool: EditMessageTool
    private lateinit var exchange: McpSyncServerExchange

    @BeforeEach
    fun setUp() {
        telegramClient = mockk()
        entityResolver = mockk()
        guardrailService = mockk(relaxed = true)
        objectMapper = jacksonObjectMapper().findAndRegisterModules()
        exchange = mockk(relaxed = true)

        tool = EditMessageTool(
            telegramClient = telegramClient,
            entityResolver = entityResolver,
            guardrailService = guardrailService,
            objectMapper = objectMapper,
            meterRegistry = SimpleMeterRegistry(),
        )
    }

    @Test
    fun `definition returns correct tool name`() {
        val def = tool.definition()
        assertEquals("edit_message", def.name())
    }

    @Test
    fun `edits message with plain text`() {
        val editedMsg = TelegramMessage(
            messageId = 55, chatId = 42, chatTitle = "Test",
            senderName = "Bot", text = "Updated text", date = Instant.now(),
        )
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { telegramClient.editMessage(42L, 55L, "Updated text", ParseMode.PLAIN) } returns editedMsg

        val result = tool.execute(
            exchange,
            mapOf("chat_id" to 42, "message_id" to 55, "text" to "Updated text"),
        )

        assertFalse(result.isError)
        verify { guardrailService.validateInput("Updated text") }
        verify { guardrailService.validateChatAccess(42L) }
    }

    @Test
    fun `edits message with HTML parse mode`() {
        val editedMsg = TelegramMessage(
            messageId = 55, chatId = 42, chatTitle = "Test",
            senderName = "Bot", text = "<b>Bold</b>", date = Instant.now(),
        )
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { telegramClient.editMessage(42L, 55L, "<b>Bold</b>", ParseMode.HTML) } returns editedMsg

        val result = tool.execute(
            exchange,
            mapOf("chat_id" to 42, "message_id" to 55, "text" to "<b>Bold</b>", "parse_mode" to "html"),
        )

        assertFalse(result.isError)
    }

    @Test
    fun `returns error when chat_id is missing`() {
        val result = tool.execute(exchange, mapOf("message_id" to 55, "text" to "update"))

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("chat_id is required"))
    }

    @Test
    fun `returns error when message_id is missing`() {
        every { entityResolver.resolve(42 as Any) } returns 42L

        val result = tool.execute(exchange, mapOf("chat_id" to 42, "text" to "update"))

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("message_id is required"))
    }

    @Test
    fun `returns error when text is missing`() {
        every { entityResolver.resolve(42 as Any) } returns 42L

        val result = tool.execute(exchange, mapOf("chat_id" to 42, "message_id" to 55))

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("text is required"))
    }

    @Test
    fun `returns error when text is blank`() {
        every { entityResolver.resolve(42 as Any) } returns 42L

        val result = tool.execute(
            exchange,
            mapOf("chat_id" to 42, "message_id" to 55, "text" to "  "),
        )

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("text must not be blank"))
    }

    @Test
    fun `returns error for invalid parse_mode`() {
        every { entityResolver.resolve(42 as Any) } returns 42L

        val result = tool.execute(
            exchange,
            mapOf("chat_id" to 42, "message_id" to 55, "text" to "test", "parse_mode" to "xml"),
        )

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("parse_mode"))
    }
}

