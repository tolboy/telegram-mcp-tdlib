package dev.telegrammcp.server.tool.message

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.service.AuditService
import dev.telegrammcp.server.service.EntityResolverService
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

class MarkAsReadToolTest {

    private lateinit var telegramClient: TelegramClientService
    private lateinit var entityResolver: EntityResolverService
    private lateinit var guardrailService: GuardrailService
    private lateinit var operationGuardService: OperationGuardService
    private lateinit var auditService: AuditService
    private lateinit var objectMapper: ObjectMapper
    private lateinit var tool: MarkAsReadTool
    private lateinit var exchange: McpSyncServerExchange

    @BeforeEach
    fun setUp() {
        telegramClient = mockk()
        entityResolver = mockk()
        guardrailService = mockk(relaxed = true)
        operationGuardService = mockk(relaxed = true)
        auditService = mockk(relaxed = true)
        objectMapper = jacksonObjectMapper().findAndRegisterModules()
        exchange = mockk(relaxed = true)

        tool = MarkAsReadTool(
            telegramClient = telegramClient,
            entityResolver = entityResolver,
            guardrailService = guardrailService,
            operationGuardService = operationGuardService,
            auditService = auditService,
            objectMapper = objectMapper,
            meterRegistry = SimpleMeterRegistry(),
        )
    }

    @Test
    fun `definition returns correct tool name`() {
        assertEquals("mark_as_read", tool.definition().name())
    }

    @Test
    fun `marks messages as read successfully`() {
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { telegramClient.viewMessages(42L, listOf(1L, 2L)) } returns true

        val result = tool.execute(
            exchange,
            mapOf("chat_id" to 42, "message_ids" to listOf(1, 2)),
        )

        assertFalse(result.isError)
        verify { operationGuardService.checkPermission("mark_as_read", any()) }
        verify { guardrailService.validateChatAccess(42L) }
    }

    @Test
    fun `returns error when message_ids is empty`() {
        every { entityResolver.resolve(42 as Any) } returns 42L

        val result = tool.execute(
            exchange,
            mapOf("chat_id" to 42, "message_ids" to emptyList<Int>()),
        )

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("must not be empty"))
    }

    @Test
    fun `returns error when chat_id is missing`() {
        val result = tool.execute(exchange, mapOf("message_ids" to listOf(1)))

        assertTrue(result.isError)
    }
}

