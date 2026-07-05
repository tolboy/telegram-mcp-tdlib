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

class GetMessageContextToolTest {

    private lateinit var telegramClient: TelegramClientService
    private lateinit var entityResolver: EntityResolverService
    private lateinit var guardrailService: GuardrailService
    private lateinit var objectMapper: ObjectMapper
    private lateinit var tool: GetMessageContextTool
    private lateinit var exchange: McpSyncServerExchange

    private fun msg(id: Long) = TelegramMessage(
        messageId = id, chatId = 42, chatTitle = "Chat",
        senderName = "User", text = "Message $id", date = Instant.now(),
    )

    @BeforeEach
    fun setUp() {
        telegramClient = mockk()
        entityResolver = mockk()
        guardrailService = mockk(relaxed = true)
        objectMapper = jacksonObjectMapper().findAndRegisterModules()
        exchange = mockk(relaxed = true)

        tool = GetMessageContextTool(
            telegramClient = telegramClient,
            entityResolver = entityResolver,
            guardrailService = guardrailService,
            objectMapper = objectMapper,
            meterRegistry = SimpleMeterRegistry(),
        )
    }

    @Test
    fun `definition returns correct tool name`() {
        assertEquals("get_message_context", tool.definition().name())
    }

    @Test
    fun `returns context messages with default size`() {
        val messages = (95..101L).map { msg(it) }
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { telegramClient.getMessageContext(42L, 98L, 6) } returns messages

        val result = tool.execute(
            exchange,
            mapOf("chat_id" to 42, "message_id" to 98),
        )

        assertFalse(result.isError)
        verify { guardrailService.validateChatAccess(42L) }
        verify { telegramClient.getMessageContext(42L, 98L, 6) }
    }

    @Test
    fun `passes custom context_size to client`() {
        val messages = (90..110L).map { msg(it) }
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { telegramClient.getMessageContext(42L, 100L, 20) } returns messages

        val result = tool.execute(
            exchange,
            mapOf("chat_id" to 42, "message_id" to 100, "context_size" to 20),
        )

        assertFalse(result.isError)
        verify { telegramClient.getMessageContext(42L, 100L, 20) }
    }

    @Test
    fun `returns error when message_id is missing`() {
        every { entityResolver.resolve(42 as Any) } returns 42L

        val result = tool.execute(exchange, mapOf("chat_id" to 42))

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("message_id is required"))
    }
}
