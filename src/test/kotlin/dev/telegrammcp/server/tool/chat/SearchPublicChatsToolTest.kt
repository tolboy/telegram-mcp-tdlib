package dev.telegrammcp.server.tool.chat

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.model.ChatInfo
import dev.telegrammcp.server.model.ChatType
import dev.telegrammcp.server.service.AuditService
import dev.telegrammcp.server.service.GuardrailService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.modelcontextprotocol.server.McpSyncServerExchange
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import io.modelcontextprotocol.spec.McpSchema

class SearchPublicChatsToolTest {

    private lateinit var telegramClient: TelegramClientService
    private lateinit var guardrailService: GuardrailService
    private lateinit var auditService: AuditService
    private lateinit var objectMapper: ObjectMapper
    private lateinit var tool: SearchPublicChatsTool
    private lateinit var exchange: McpSyncServerExchange

    @BeforeEach
    fun setUp() {
        telegramClient = mockk()
        guardrailService = mockk(relaxed = true)
        auditService = mockk(relaxed = true)
        objectMapper = jacksonObjectMapper().findAndRegisterModules()
        exchange = mockk(relaxed = true)

        tool = SearchPublicChatsTool(
            telegramClient = telegramClient,
            guardrailService = guardrailService,
            auditService = auditService,
            objectMapper = objectMapper,
            meterRegistry = SimpleMeterRegistry(),
        )
    }

    @Test
    fun `definition returns correct tool name`() {
        assertEquals("search_public_chats", tool.definition().name())
    }

    @Test
    fun `searches public chats successfully`() {
        val chats = listOf(
            ChatInfo(1L, "Kotlin", ChatType.CHANNEL, 5000, username = "kotlin"),
        )
        every { guardrailService.isChatAllowed(1L) } returns true
        every { telegramClient.searchPublicChats("kotlin", 20) } returns chats

        val result = tool.execute(exchange, mapOf("query" to "kotlin"))

        assertFalse(result.isError)
        verify { guardrailService.validateInput("kotlin") }
    }

    @Test
    fun `filters chats rejected by the static allow-list`() {
        every {
            telegramClient.searchPublicChats("kotlin", 20)
        } returns listOf(
            ChatInfo(1L, "Allowed", ChatType.CHANNEL, username = "allowed"),
            ChatInfo(2L, "Forbidden sentinel", ChatType.CHANNEL, username = "forbidden"),
        )
        every { guardrailService.isChatAllowed(1L) } returns true
        every { guardrailService.isChatAllowed(2L) } returns false

        val result = tool.execute(exchange, mapOf("query" to "kotlin"))

        assertFalse(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("Allowed"))
        assertFalse(text.contains("Forbidden sentinel"))
    }

    @Test
    fun `returns error when query is missing`() {
        val result = tool.execute(exchange, emptyMap())
        assertTrue(result.isError)
    }

    @Test
    fun `returns error when query is blank`() {
        val result = tool.execute(exchange, mapOf("query" to "  "))
        assertTrue(result.isError)
    }
}
