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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateProfileToolTest {

    private lateinit var telegramClient: TelegramClientService
    private lateinit var guardrailService: GuardrailService
    private lateinit var operationGuardService: OperationGuardService
    private lateinit var auditService: AuditService
    private lateinit var objectMapper: ObjectMapper
    private lateinit var tool: UpdateProfileTool
    private lateinit var exchange: McpSyncServerExchange

    @BeforeEach
    fun setUp() {
        telegramClient = mockk()
        guardrailService = mockk(relaxed = true)
        operationGuardService = mockk(relaxed = true)
        auditService = mockk(relaxed = true)
        objectMapper = jacksonObjectMapper().findAndRegisterModules()
        exchange = mockk(relaxed = true)

        tool = UpdateProfileTool(
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
        assertEquals("update_profile", tool.definition().name())
    }

    @Test
    fun `updates profile successfully`() {
        every { telegramClient.updateProfile("John", "Doe", "Hello!") } returns true

        val result = tool.execute(
            exchange,
            mapOf("first_name" to "John", "last_name" to "Doe", "bio" to "Hello!"),
        )

        assertFalse(result.isError)
        verify { operationGuardService.checkPermission("update_profile", any()) }
    }

    @Test
    fun `returns error when no fields provided`() {
        val result = tool.execute(exchange, emptyMap())
        assertTrue(result.isError)
    }

    @Test
    fun `returns error when first_name is too long`() {
        val result = tool.execute(exchange, mapOf("first_name" to "a".repeat(65)))
        assertTrue(result.isError)
    }

    @Test
    fun `returns error when bio is too long`() {
        val result = tool.execute(exchange, mapOf("bio" to "b".repeat(71)))
        assertTrue(result.isError)
    }
}
