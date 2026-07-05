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
import io.modelcontextprotocol.spec.McpSchema
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchGlobalToolTest {

    private lateinit var telegramClient: TelegramClientService
    private lateinit var guardrailService: GuardrailService
    private lateinit var auditService: AuditService
    private lateinit var objectMapper: ObjectMapper
    private lateinit var tool: SearchGlobalTool
    private lateinit var exchange: McpSyncServerExchange

    @BeforeEach
    fun setUp() {
        telegramClient = mockk()
        guardrailService = mockk(relaxed = true)
        auditService = mockk(relaxed = true)
        objectMapper = jacksonObjectMapper().findAndRegisterModules()
        exchange = mockk(relaxed = true)

        tool = SearchGlobalTool(
            telegramClient = telegramClient,
            guardrailService = guardrailService,
            auditService = auditService,
            objectMapper = objectMapper,
            meterRegistry = SimpleMeterRegistry(),
        )
    }

    @Test
    fun `definition returns correct tool name`() {
        assertEquals("search_global", tool.definition().name())
    }

    @Test
    fun `searches globally with default limit`() {
        val messages = listOf(
            TelegramMessage(messageId = 1, chatId = 10, chatTitle = null, senderName = "User", text = "kotlin rocks", date = Instant.now()),
        )
        every { telegramClient.searchGlobal("kotlin", 20) } returns messages

        val result = tool.execute(exchange, mapOf("query" to "kotlin"))

        assertFalse(result.isError)
        verify { guardrailService.validateInput("kotlin") }
    }

    @Test
    fun `searches globally with custom limit`() {
        every { telegramClient.searchGlobal("test", 5) } returns emptyList()

        val result = tool.execute(exchange, mapOf("query" to "test", "limit" to 5))

        assertFalse(result.isError)
    }

    @Test
    fun `returns error when query is missing`() {
        val result = tool.execute(exchange, emptyMap())

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("query is required"))
    }

    @Test
    fun `returns error when query is blank`() {
        val result = tool.execute(exchange, mapOf("query" to "  "))

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("must not be blank"))
    }

    @Test
    fun `returns error when query exceeds max length`() {
        val longQuery = "a".repeat(257)

        val result = tool.execute(exchange, mapOf("query" to longQuery))

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("maximum length"))
    }
}


