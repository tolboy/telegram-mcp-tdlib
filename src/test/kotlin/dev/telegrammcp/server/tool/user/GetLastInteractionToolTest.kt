package dev.telegrammcp.server.tool.user

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.model.TelegramMessage
import dev.telegrammcp.server.service.AuditService
import dev.telegrammcp.server.service.EntityResolverService
import dev.telegrammcp.server.service.GuardrailService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.modelcontextprotocol.server.McpSyncServerExchange
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GetLastInteractionToolTest {

    private lateinit var telegramClient: TelegramClientService
    private lateinit var entityResolver: EntityResolverService
    private lateinit var guardrailService: GuardrailService
    private lateinit var auditService: AuditService
    private lateinit var objectMapper: ObjectMapper
    private lateinit var tool: GetLastInteractionTool
    private lateinit var exchange: McpSyncServerExchange

    @BeforeEach
    fun setUp() {
        telegramClient = mockk()
        entityResolver = mockk()
        guardrailService = mockk(relaxed = true)
        auditService = mockk(relaxed = true)
        objectMapper = jacksonObjectMapper().findAndRegisterModules()
        exchange = mockk(relaxed = true)

        tool = GetLastInteractionTool(
            telegramClient = telegramClient,
            entityResolver = entityResolver,
            guardrailService = guardrailService,
            auditService = auditService,
            objectMapper = objectMapper,
            meterRegistry = SimpleMeterRegistry(),
        )
    }

    @Test
    fun `definition returns correct tool name`() {
        assertEquals("get_last_interaction", tool.definition().name())
    }

    @Test
    fun `returns last interaction successfully`() {
        val msg = TelegramMessage(10, 99, "Private", "Alice", text = "Hi!", date = Instant.now())
        every { entityResolver.resolve(99 as Any) } returns 99L
        every { telegramClient.getLastInteractionWithContact(99L) } returns msg

        val result = tool.execute(exchange, mapOf("contact_id" to 99))

        assertFalse(result.isError)
        verify { guardrailService.validateChatAccess(99L) }
    }

    @Test
    fun `returns null message when no interaction`() {
        every { entityResolver.resolve(99 as Any) } returns 99L
        every { telegramClient.getLastInteractionWithContact(99L) } returns null

        val result = tool.execute(exchange, mapOf("contact_id" to 99))

        assertFalse(result.isError)
        verify(exactly = 0) { guardrailService.validateChatAccess(any()) }
    }

    @Test
    fun `returns error when contact_id is missing`() {
        val result = tool.execute(exchange, emptyMap())
        assertTrue(result.isError)
    }
}
