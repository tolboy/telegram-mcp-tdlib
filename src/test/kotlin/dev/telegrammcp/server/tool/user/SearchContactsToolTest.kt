package dev.telegrammcp.server.tool.user

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.model.ContactInfo
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

class SearchContactsToolTest {

    private lateinit var telegramClient: TelegramClientService
    private lateinit var guardrailService: GuardrailService
    private lateinit var auditService: AuditService
    private lateinit var objectMapper: ObjectMapper
    private lateinit var tool: SearchContactsTool
    private lateinit var exchange: McpSyncServerExchange

    @BeforeEach
    fun setUp() {
        telegramClient = mockk()
        guardrailService = mockk(relaxed = true)
        auditService = mockk(relaxed = true)
        objectMapper = jacksonObjectMapper().findAndRegisterModules()
        exchange = mockk(relaxed = true)

        tool = SearchContactsTool(
            telegramClient = telegramClient,
            guardrailService = guardrailService,
            auditService = auditService,
            objectMapper = objectMapper,
            meterRegistry = SimpleMeterRegistry(),
        )
    }

    @Test
    fun `definition returns correct tool name`() {
        assertEquals("search_contacts", tool.definition().name())
    }

    @Test
    fun `searches contacts successfully`() {
        val contacts = listOf(
            ContactInfo(99L, "Alice", "Smith", "alice", "+123456"),
        )
        every { telegramClient.searchContactsByQuery("Alice", 50) } returns contacts

        val result = tool.execute(exchange, mapOf("query" to "Alice"))

        assertFalse(result.isError)
        verify { guardrailService.validateInput("Alice") }
    }

    @Test
    fun `returns error when query is missing`() {
        val result = tool.execute(exchange, emptyMap())
        assertTrue(result.isError)
    }
}
