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

class SearchMessagesToolTest {

    private lateinit var telegramClient: TelegramClientService
    private lateinit var entityResolver: EntityResolverService
    private lateinit var guardrailService: GuardrailService
    private lateinit var objectMapper: ObjectMapper
    private lateinit var tool: SearchMessagesTool
    private lateinit var exchange: McpSyncServerExchange

    @BeforeEach
    fun setUp() {
        telegramClient = mockk()
        entityResolver = mockk()
        guardrailService = mockk(relaxed = true)
        objectMapper = jacksonObjectMapper().findAndRegisterModules()
        exchange = mockk(relaxed = true)

        tool = SearchMessagesTool(
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
        assertEquals("search_messages", def.name())
    }

    @Test
    fun `searches messages with default parameters`() {
        val messages = listOf(
            TelegramMessage(
                messageId = 1, chatId = 42, chatTitle = "Test",
                senderName = "Alice", text = "kotlin coroutines rock", date = Instant.now(),
            ),
        )
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { telegramClient.searchMessages(42L, "kotlin", 0L, 20) } returns messages

        val result = tool.execute(
            exchange,
            mapOf("chat_id" to 42, "query" to "kotlin"),
        )

        assertFalse(result.isError)
        verify { guardrailService.validateInput("kotlin") }
        verify { guardrailService.validateChatAccess(42L) }
    }

    @Test
    fun `searches with custom limit and offset`() {
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { telegramClient.searchMessages(42L, "test", 50L, 10) } returns emptyList()

        val result = tool.execute(
            exchange,
            mapOf("chat_id" to 42, "query" to "test", "limit" to 10, "offset" to 50),
        )

        assertFalse(result.isError)
        verify { telegramClient.searchMessages(42L, "test", 50L, 10) }
    }

    @Test
    fun `clamps limit to max 100`() {
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { telegramClient.searchMessages(42L, "test", 0L, 100) } returns emptyList()

        val result = tool.execute(
            exchange,
            mapOf("chat_id" to 42, "query" to "test", "limit" to 999),
        )

        assertFalse(result.isError)
        verify { telegramClient.searchMessages(42L, "test", 0L, 100) }
    }

    @Test
    fun `returns error when chat_id is missing`() {
        val result = tool.execute(exchange, mapOf("query" to "test"))

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("chat_id is required"))
    }

    @Test
    fun `returns error when query is missing`() {
        every { entityResolver.resolve(42 as Any) } returns 42L

        val result = tool.execute(exchange, mapOf("chat_id" to 42))

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("query is required"))
    }

    @Test
    fun `returns error when query is blank`() {
        every { entityResolver.resolve(42 as Any) } returns 42L

        val result = tool.execute(
            exchange,
            mapOf("chat_id" to 42, "query" to "  "),
        )

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("query must not be blank"))
    }

    @Test
    fun `returns error when query exceeds max length`() {
        every { entityResolver.resolve(42 as Any) } returns 42L
        val longQuery = "a".repeat(257)

        val result = tool.execute(
            exchange,
            mapOf("chat_id" to 42, "query" to longQuery),
        )

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("exceeds maximum length"))
    }

    @Test
    fun `accepts string offset`() {
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { telegramClient.searchMessages(42L, "test", 25L, 20) } returns emptyList()

        val result = tool.execute(
            exchange,
            mapOf("chat_id" to 42, "query" to "test", "offset" to "25"),
        )

        assertFalse(result.isError)
    }

    @Test
    fun `returns error for invalid offset`() {
        every { entityResolver.resolve(42 as Any) } returns 42L

        val result = tool.execute(
            exchange,
            mapOf("chat_id" to 42, "query" to "test", "offset" to "not_a_number"),
        )

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("offset must be a valid number"))
    }
}

