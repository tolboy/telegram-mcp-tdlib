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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VotePollToolTest {

    private lateinit var telegramClient: TelegramClientService
    private lateinit var entityResolver: EntityResolverService
    private lateinit var guardrailService: GuardrailService
    private lateinit var operationGuardService: OperationGuardService
    private lateinit var auditService: AuditService
    private lateinit var objectMapper: ObjectMapper
    private lateinit var tool: VotePollTool
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

        tool = VotePollTool(
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
        assertEquals("vote_poll", tool.definition().name())
    }

    @Test
    fun `votes with selected option indexes`() {
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { telegramClient.setPollAnswer(42L, 100L, listOf(0, 2)) } returns true

        val result = tool.execute(exchange, mapOf("chat_id" to 42, "message_id" to 100, "option_ids" to listOf(0, 2)))

        assertFalse(result.isError)
        verify { telegramClient.setPollAnswer(42L, 100L, listOf(0, 2)) }
    }

    @Test
    fun `rejects negative option indexes`() {
        every { entityResolver.resolve(42 as Any) } returns 42L

        val result = tool.execute(exchange, mapOf("chat_id" to 42, "message_id" to 100, "option_ids" to listOf(-1)))

        assertTrue(result.isError)
        verify(exactly = 0) { telegramClient.setPollAnswer(any(), any(), any()) }
    }

    @Test
    fun `rejects missing option_ids`() {
        every { entityResolver.resolve(42 as Any) } returns 42L

        val result = tool.execute(exchange, mapOf("chat_id" to 42, "message_id" to 100))

        assertTrue(result.isError)
    }
}

class ClosePollToolTest {

    private lateinit var telegramClient: TelegramClientService
    private lateinit var entityResolver: EntityResolverService
    private lateinit var guardrailService: GuardrailService
    private lateinit var operationGuardService: OperationGuardService
    private lateinit var auditService: AuditService
    private lateinit var objectMapper: ObjectMapper
    private lateinit var tool: ClosePollTool
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

        tool = ClosePollTool(
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
        assertEquals("close_poll", tool.definition().name())
    }

    @Test
    fun `closes poll`() {
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { telegramClient.stopPoll(42L, 100L) } returns true

        val result = tool.execute(exchange, mapOf("chat_id" to 42, "message_id" to 100, "confirmed" to true))

        assertFalse(result.isError)
        verify { telegramClient.stopPoll(42L, 100L) }
    }

    @Test
    fun `checks operation guard before closing`() {
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { telegramClient.stopPoll(42L, 100L) } returns true

        tool.execute(exchange, mapOf("chat_id" to 42, "message_id" to 100))

        verify { operationGuardService.checkPermission("close_poll", any()) }
    }
}
