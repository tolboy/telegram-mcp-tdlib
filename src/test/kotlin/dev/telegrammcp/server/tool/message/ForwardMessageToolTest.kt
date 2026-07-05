package dev.telegrammcp.server.tool.message

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.model.TelegramMessage
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
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ForwardMessageToolTest {

    private lateinit var telegramClient: TelegramClientService
    private lateinit var entityResolver: EntityResolverService
    private lateinit var guardrailService: GuardrailService
    private lateinit var operationGuardService: OperationGuardService
    private lateinit var auditService: AuditService
    private lateinit var objectMapper: ObjectMapper
    private lateinit var tool: ForwardMessageTool
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

        tool = ForwardMessageTool(
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
        assertEquals("forward_message", tool.definition().name())
    }

    @Test
    fun `forwards messages successfully`() {
        val forwarded = listOf(
            TelegramMessage(messageId = 100, chatId = 2, chatTitle = "Dest", senderName = "Bot", text = "Hello", date = Instant.now()),
        )
        every { entityResolver.resolve(1 as Any) } returns 1L
        every { entityResolver.resolve(2 as Any) } returns 2L
        every { telegramClient.forwardMessages(1L, 2L, listOf(10L)) } returns forwarded

        val result = tool.execute(
            exchange,
            mapOf("from_chat_id" to 1, "to_chat_id" to 2, "message_ids" to listOf(10)),
        )

        assertFalse(result.isError)
        verify { guardrailService.validateChatAccess(1L) }
        verify { guardrailService.validateChatAccess(2L) }
    }

    @Test
    fun `returns error when from_chat_id is missing`() {
        val result = tool.execute(exchange, mapOf("to_chat_id" to 2, "message_ids" to listOf(1)))

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("from_chat_id is required"))
    }

    @Test
    fun `returns error when to_chat_id is missing`() {
        every { entityResolver.resolve(1 as Any) } returns 1L

        val result = tool.execute(exchange, mapOf("from_chat_id" to 1, "message_ids" to listOf(1)))

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("to_chat_id is required"))
    }

    @Test
    fun `returns error when message_ids is empty`() {
        every { entityResolver.resolve(1 as Any) } returns 1L
        every { entityResolver.resolve(2 as Any) } returns 2L

        val result = tool.execute(
            exchange,
            mapOf("from_chat_id" to 1, "to_chat_id" to 2, "message_ids" to emptyList<Int>()),
        )

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("must not be empty"))
    }
}


