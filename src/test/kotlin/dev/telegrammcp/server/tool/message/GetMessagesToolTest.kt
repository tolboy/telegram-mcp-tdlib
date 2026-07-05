package dev.telegrammcp.server.tool.message

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
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

class GetMessagesToolTest {

    private lateinit var telegramClient: TelegramClientService
    private lateinit var entityResolver: EntityResolverService
    private lateinit var guardrailService: GuardrailService
    private lateinit var objectMapper: ObjectMapper
    private lateinit var tool: GetMessagesTool
    private lateinit var exchange: McpSyncServerExchange

    @BeforeEach
    fun setUp() {
        telegramClient = mockk()
        entityResolver = mockk()
        guardrailService = mockk(relaxed = true)
        objectMapper = jacksonObjectMapper().findAndRegisterModules()
        exchange = mockk(relaxed = true)

        tool = GetMessagesTool(
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
        assertEquals("get_messages", def.name())
    }

    @Test
    fun `gets messages around a target with default context size`() {
        val messages = listOf(
            TelegramMessage(
                messageId = 10, chatId = 42, chatTitle = "Test",
                senderName = "Alice", text = "Hello", date = Instant.now(),
            ),
        )
        every { entityResolver.resolve(42 as Any) } returns 42L
        // default context_size = 5, so total = 5*2+1 = 11, offset = -5
        every { telegramClient.getHistory(42L, 100L, -5, 11) } returns messages

        val result = tool.execute(exchange, mapOf("chat_id" to 42, "message_id" to 100))

        assertFalse(result.isError)
        verify { guardrailService.validateChatAccess(42L) }
    }

    @Test
    fun `gets messages with custom context size`() {
        every { entityResolver.resolve(42 as Any) } returns 42L
        // context_size = 10, total = 10*2+1 = 21, offset = -10
        every { telegramClient.getHistory(42L, 100L, -10, 21) } returns emptyList()

        val result = tool.execute(
            exchange,
            mapOf("chat_id" to 42, "message_id" to 100, "context_size" to 10),
        )

        assertFalse(result.isError)
        verify { telegramClient.getHistory(42L, 100L, -10, 21) }
    }

    @Test
    fun `clamps context size to max 50`() {
        every { entityResolver.resolve(42 as Any) } returns 42L
        // context_size clamped to 50, total = 50*2+1 = 101, offset = -50
        every { telegramClient.getHistory(42L, 100L, -50, 101) } returns emptyList()

        val result = tool.execute(
            exchange,
            mapOf("chat_id" to 42, "message_id" to 100, "context_size" to 999),
        )

        assertFalse(result.isError)
        verify { telegramClient.getHistory(42L, 100L, -50, 101) }
    }

    @Test
    fun `returns error when chat_id is missing`() {
        val result = tool.execute(exchange, mapOf("message_id" to 100))

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("chat_id is required"))
    }

    @Test
    fun `returns error when message_id is missing`() {
        every { entityResolver.resolve(42 as Any) } returns 42L

        val result = tool.execute(exchange, mapOf("chat_id" to 42))

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("message_id is required"))
    }

    @Test
    fun `returns error for invalid message_id type`() {
        every { entityResolver.resolve(42 as Any) } returns 42L

        val result = tool.execute(
            exchange,
            mapOf("chat_id" to 42, "message_id" to "not_a_number"),
        )

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("message_id must be a valid number"))
    }

    @Test
    fun `accepts string message_id`() {
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { telegramClient.getHistory(42L, 100L, -5, 11) } returns emptyList()

        val result = tool.execute(
            exchange,
            mapOf("chat_id" to 42, "message_id" to "100"),
        )

        assertFalse(result.isError)
    }
}

