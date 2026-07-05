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

class GetHistoryToolTest {

    private lateinit var telegramClient: TelegramClientService
    private lateinit var entityResolver: EntityResolverService
    private lateinit var guardrailService: GuardrailService
    private lateinit var objectMapper: ObjectMapper
    private lateinit var tool: GetHistoryTool
    private lateinit var exchange: McpSyncServerExchange

    @BeforeEach
    fun setUp() {
        telegramClient = mockk()
        entityResolver = mockk()
        guardrailService = mockk(relaxed = true)
        objectMapper = jacksonObjectMapper().findAndRegisterModules()
        exchange = mockk(relaxed = true)

        tool = GetHistoryTool(
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
        assertEquals("get_history", def.name())
    }

    @Test
    fun `execute returns messages on success`() {
        val messages = listOf(
            TelegramMessage(
                messageId = 1,
                chatId = 42,
                chatTitle = "Test Chat",
                senderName = "Alice",
                text = "Hello world",
                date = Instant.now(),
            ),
        )
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { telegramClient.getHistory(42L, 0L, 0, 20) } returns messages

        val result = tool.execute(exchange, mapOf("chat_id" to 42))

        assertFalse(result.isError)
        verify { guardrailService.validateChatAccess(42L) }
    }

    @Test
    fun `execute returns error when chat_id is missing`() {
        val result = tool.execute(exchange, emptyMap())

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("chat_id is required"))
    }

    @Test
    fun `execute clamps limit to valid range`() {
        every { entityResolver.resolve(1L as Any) } returns 1L
        every { telegramClient.getHistory(1L, 0L, 0, 100) } returns emptyList()

        tool.execute(exchange, mapOf("chat_id" to 1L, "limit" to 999))

        verify { telegramClient.getHistory(1L, 0L, 0, 100) }
    }

    @Test
    fun `execute supports pagination via from_message_id`() {
        every { entityResolver.resolve(1L as Any) } returns 1L
        every { telegramClient.getHistory(1L, 500L, 0, 20) } returns emptyList()

        tool.execute(exchange, mapOf("chat_id" to 1L, "from_message_id" to 500L))

        verify { telegramClient.getHistory(1L, 500L, 0, 20) }
    }
}

