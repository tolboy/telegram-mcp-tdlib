package dev.telegrammcp.server.tool.message

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.model.TelegramMessage
import dev.telegrammcp.server.service.AuditService
import dev.telegrammcp.server.service.GuardrailService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.modelcontextprotocol.server.McpSyncServerExchange
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessageFromLinkToolTest {

    private lateinit var telegramClient: TelegramClientService
    private lateinit var guardrailService: GuardrailService
    private lateinit var auditService: AuditService
    private lateinit var objectMapper: ObjectMapper
    private lateinit var tool: MessageFromLinkTool
    private lateinit var exchange: McpSyncServerExchange

    @BeforeEach
    fun setUp() {
        telegramClient = mockk()
        guardrailService = mockk(relaxed = true)
        auditService = mockk(relaxed = true)
        objectMapper = jacksonObjectMapper().findAndRegisterModules()
        exchange = mockk(relaxed = true)

        tool = MessageFromLinkTool(
            telegramClient = telegramClient,
            guardrailService = guardrailService,
            auditService = auditService,
            objectMapper = objectMapper,
            meterRegistry = SimpleMeterRegistry(),
        )
    }

    @Test
    fun `definition returns correct tool name`() {
        assertEquals("message_from_link", tool.definition().name())
    }

    @Test
    fun `resolves message from link successfully`() {
        val msg = TelegramMessage(789, 123, "Channel", "Alice", text = "Hello", date = Instant.now())
        every { telegramClient.getMessageByLink("https://t.me/c/123/789") } returns msg

        val result = tool.execute(exchange, mapOf("link" to "https://t.me/c/123/789"))

        assertFalse(result.isError)
        verify { guardrailService.validateInput("https://t.me/c/123/789") }
        verify { guardrailService.validateChatAccess(123L) }
    }

    @Test
    fun `returns error when link is missing`() {
        val result = tool.execute(exchange, emptyMap())
        assertTrue(result.isError)
    }

    @Test
    fun `returns error when link is not a valid t_me URL`() {
        val result = tool.execute(exchange, mapOf("link" to "https://example.com/123"))
        assertTrue(result.isError)
    }
}
