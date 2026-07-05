package dev.telegrammcp.server.tool.user

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.model.ContactInfo
import dev.telegrammcp.server.service.AuditService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ListContactsToolTest {

    private lateinit var telegramClient: TelegramClientService
    private lateinit var auditService: AuditService
    private lateinit var objectMapper: ObjectMapper
    private lateinit var tool: ListContactsTool
    private lateinit var exchange: McpSyncServerExchange

    @BeforeEach
    fun setUp() {
        telegramClient = mockk()
        auditService = mockk(relaxed = true)
        objectMapper = jacksonObjectMapper().findAndRegisterModules()
        exchange = mockk(relaxed = true)

        tool = ListContactsTool(
            telegramClient = telegramClient,
            auditService = auditService,
            objectMapper = objectMapper,
            meterRegistry = SimpleMeterRegistry(),
        )
    }

    @Test
    fun `definition returns correct tool name`() {
        assertEquals("list_contacts", tool.definition().name())
    }

    @Test
    fun `returns all contacts without filter`() {
        val contacts = listOf(
            ContactInfo(userId = 1, firstName = "Alice", username = "alice"),
            ContactInfo(userId = 2, firstName = "Bob", lastName = "Smith"),
        )
        every { telegramClient.getContacts() } returns contacts

        val result = tool.execute(exchange, emptyMap())

        assertFalse(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("Alice"))
        assertTrue(text.contains("Bob"))
    }

    @Test
    fun `filters contacts by query`() {
        val contacts = listOf(
            ContactInfo(userId = 1, firstName = "Alice", username = "alice"),
            ContactInfo(userId = 2, firstName = "Bob", lastName = "Smith"),
        )
        every { telegramClient.getContacts() } returns contacts

        val result = tool.execute(exchange, mapOf("query" to "alice"))

        assertFalse(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("Alice"))
        assertFalse(text.contains("Bob"))
    }

    @Test
    fun `returns empty list when no contacts match query`() {
        val contacts = listOf(
            ContactInfo(userId = 1, firstName = "Alice"),
        )
        every { telegramClient.getContacts() } returns contacts

        val result = tool.execute(exchange, mapOf("query" to "xyz"))

        assertFalse(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("[]"))
    }

    @Test
    fun `returns error when client throws`() {
        every { telegramClient.getContacts() } throws RuntimeException("Not available")

        val result = tool.execute(exchange, emptyMap())

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("Not available"))
    }
}
