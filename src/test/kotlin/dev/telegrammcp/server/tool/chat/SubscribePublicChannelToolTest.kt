package dev.telegrammcp.server.tool.chat

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.model.ChatInfo
import dev.telegrammcp.server.model.ChatType
import dev.telegrammcp.server.service.AuditService
import dev.telegrammcp.server.service.GuardrailService
import dev.telegrammcp.server.service.OperationGuardService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubscribePublicChannelToolTest {

    private lateinit var telegramClient: TelegramClientService
    private lateinit var guardrailService: GuardrailService
    private lateinit var operationGuardService: OperationGuardService
    private lateinit var auditService: AuditService
    private lateinit var objectMapper: ObjectMapper
    private lateinit var tool: SubscribePublicChannelTool
    private lateinit var exchange: McpSyncServerExchange

    @BeforeEach
    fun setUp() {
        telegramClient = mockk()
        guardrailService = mockk(relaxed = true)
        operationGuardService = mockk(relaxed = true)
        auditService = mockk(relaxed = true)
        objectMapper = jacksonObjectMapper().findAndRegisterModules()
        exchange = mockk(relaxed = true)

        tool = SubscribePublicChannelTool(
            telegramClient = telegramClient,
            guardrailService = guardrailService,
            operationGuardService = operationGuardService,
            auditService = auditService,
            objectMapper = objectMapper,
            meterRegistry = SimpleMeterRegistry(),
        )
    }

    @Test
    fun `definition returns correct tool name`() {
        assertEquals("subscribe_public_channel", tool.definition().name())
    }

    @Test
    fun `subscribes to channel successfully`() {
        val chat = ChatInfo(chatId = 300L, title = "PublicChannel", type = ChatType.CHANNEL)
        every { telegramClient.joinPublicChat("public_channel") } returns chat

        val result = tool.execute(exchange, mapOf("channel" to "public_channel"))

        assertFalse(result.isError)
        verify { telegramClient.joinPublicChat("public_channel") }
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("PublicChannel"))
    }

    @Test
    fun `returns already_member when already subscribed`() {
        every { telegramClient.joinPublicChat("public_channel") } throws
            RuntimeException("USER_ALREADY_PARTICIPANT")

        val result = tool.execute(exchange, mapOf("channel" to "public_channel"))

        assertFalse(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("already") || text.contains("already_member"), "Expected already-member indicator in: $text")
    }

    @Test
    fun `returns error when channel is missing`() {
        val result = tool.execute(exchange, emptyMap())

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("channel is required"), "Expected 'channel is required' in: $text")
    }
}
