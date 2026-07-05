package dev.telegrammcp.server.tool.user

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
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

class AddContactToolTest {

    private lateinit var telegramClient: TelegramClientService
    private lateinit var guardrailService: GuardrailService
    private lateinit var operationGuardService: OperationGuardService
    private lateinit var auditService: AuditService
    private lateinit var objectMapper: ObjectMapper
    private lateinit var tool: AddContactTool
    private lateinit var exchange: McpSyncServerExchange

    @BeforeEach
    fun setUp() {
        telegramClient = mockk()
        guardrailService = mockk(relaxed = true)
        operationGuardService = mockk(relaxed = true)
        auditService = mockk(relaxed = true)
        objectMapper = jacksonObjectMapper().findAndRegisterModules()
        exchange = mockk(relaxed = true)

        tool = AddContactTool(
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
        assertEquals("add_contact", tool.definition().name())
    }

    @Test
    fun `adds contact with first name only`() {
        every { telegramClient.addContact(99L, "John", null, null) } returns true

        val result = tool.execute(
            exchange,
            mapOf("user_id" to 99, "first_name" to "John"),
        )

        assertFalse(result.isError)
        verify { guardrailService.validateInput("John") }
    }

    @Test
    fun `adds contact with full info`() {
        every { telegramClient.addContact(99L, "John", "Doe", "+1234567890") } returns true

        val result = tool.execute(
            exchange,
            mapOf("user_id" to 99, "first_name" to "John", "last_name" to "Doe", "phone_number" to "+1234567890"),
        )

        assertFalse(result.isError)
        verify { guardrailService.validateInput("John") }
        verify { guardrailService.validateInput("Doe") }
    }

    @Test
    fun `returns error when user_id is missing`() {
        val result = tool.execute(exchange, mapOf("first_name" to "John"))

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("user_id is required"))
    }

    @Test
    fun `returns error when first_name is missing`() {
        val result = tool.execute(exchange, mapOf("user_id" to 99))

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("first_name is required"))
    }
}

