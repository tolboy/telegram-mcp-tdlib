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

class JoinChatByLinkToolTest {

    private lateinit var telegramClient: TelegramClientService
    private lateinit var guardrailService: GuardrailService
    private lateinit var operationGuardService: OperationGuardService
    private lateinit var auditService: AuditService
    private lateinit var objectMapper: ObjectMapper
    private lateinit var tool: JoinChatByLinkTool
    private lateinit var exchange: McpSyncServerExchange

    @BeforeEach
    fun setUp() {
        telegramClient = mockk()
        guardrailService = mockk(relaxed = true)
        operationGuardService = mockk(relaxed = true)
        auditService = mockk(relaxed = true)
        objectMapper = jacksonObjectMapper().findAndRegisterModules()
        exchange = mockk(relaxed = true)

        tool = JoinChatByLinkTool(
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
        assertEquals("join_chat_by_link", tool.definition().name())
    }

    @Test
    fun `joins chat and returns chat info`() {
        val chat = ChatInfo(chatId = 200L, title = "NewChat", type = ChatType.GROUP)
        every { telegramClient.joinChatByInviteLink("https://t.me/+abc") } returns chat

        val result = tool.execute(
            exchange,
            mapOf("link" to "https://t.me/+abc", "confirmed" to true),
        )

        assertFalse(result.isError)
        verify { telegramClient.joinChatByInviteLink("https://t.me/+abc") }
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("NewChat"))
    }

    @Test
    fun `returns error when link is missing`() {
        val result = tool.execute(exchange, mapOf("confirmed" to true))

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("link is required"), "Expected 'link is required' in: $text")
    }

    @Test
    fun `returns error when link is blank`() {
        val result = tool.execute(exchange, mapOf("link" to "  ", "confirmed" to true))

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("link") || text.contains("blank") || text.contains("empty"), "Expected blank-link error in: $text")
    }
}
