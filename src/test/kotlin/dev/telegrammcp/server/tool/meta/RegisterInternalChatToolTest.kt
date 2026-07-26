package dev.telegrammcp.server.tool.meta

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.exception.ChatNotAllowedException
import dev.telegrammcp.server.service.AntiSpamGuardService
import dev.telegrammcp.server.service.AuditService
import dev.telegrammcp.server.service.GuardrailService
import dev.telegrammcp.server.service.OperationGuardService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RegisterInternalChatToolTest {

    private val objectMapper = jacksonObjectMapper().findAndRegisterModules()
    private lateinit var antiSpamGuardService: AntiSpamGuardService
    private lateinit var guardrailService: GuardrailService
    private lateinit var operationGuardService: OperationGuardService
    private lateinit var tool: RegisterInternalChatTool

    @BeforeEach
    fun setUp() {
        antiSpamGuardService = mockk(relaxed = true)
        guardrailService = mockk(relaxed = true)
        operationGuardService = mockk(relaxed = true)
        tool = RegisterInternalChatTool(
            antiSpamGuardService = antiSpamGuardService,
            guardrailService = guardrailService,
            operationGuardService = operationGuardService,
            auditService = mockk<AuditService>(relaxed = true),
            objectMapper = objectMapper,
            meterRegistry = SimpleMeterRegistry(),
        )
    }

    @Test
    fun `validates allow-list before registering internal chat`() {
        val result = tool.execute(
            mockk<McpSyncServerExchange>(relaxed = true),
            mapOf("chat_id" to 42L, "confirmed" to true),
        )

        assertFalse(result.isError)
        verifyOrder {
            guardrailService.validateChatAccess(42L)
            antiSpamGuardService.registerInternalChat(42L, any())
        }
    }

    @Test
    fun `does not register chat rejected by allow-list`() {
        every { guardrailService.validateChatAccess(42L) } throws ChatNotAllowedException(42L)

        val result = tool.execute(
            mockk<McpSyncServerExchange>(relaxed = true),
            mapOf("chat_id" to 42L, "confirmed" to true),
        )

        assertTrue(result.isError)
        verify(exactly = 0) { antiSpamGuardService.registerInternalChat(any(), any()) }
    }

    @Test
    fun `success response does not disclose the complete internal chat registry`() {
        every { antiSpamGuardService.internalChatIds(any()) } returns setOf(42L, 999_999L)

        val result = tool.execute(
            mockk<McpSyncServerExchange>(relaxed = true),
            mapOf("chat_id" to "42", "confirmed" to true),
        )

        assertFalse(result.isError)
        val payload = objectMapper.readTree((result.content.first() as McpSchema.TextContent).text())
        assertEquals(setOf("chat_id", "internal"), payload.fieldNames().asSequence().toSet())
        assertEquals(42L, payload["chat_id"].asLong())
        assertTrue(payload["internal"].asBoolean())
        assertFalse(payload.toString().contains("999999"))
        verify(exactly = 0) { antiSpamGuardService.internalChatIds(any()) }
    }
}
