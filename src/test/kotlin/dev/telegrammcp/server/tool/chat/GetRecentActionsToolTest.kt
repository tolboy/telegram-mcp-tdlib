package dev.telegrammcp.server.tool.chat

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.model.AdminActionInfo
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

class GetRecentActionsToolTest {

    private lateinit var telegramClient: TelegramClientService
    private lateinit var entityResolver: EntityResolverService
    private lateinit var guardrailService: GuardrailService
    private lateinit var auditService: AuditService
    private lateinit var objectMapper: ObjectMapper
    private lateinit var tool: GetRecentActionsTool
    private lateinit var exchange: McpSyncServerExchange

    @BeforeEach
    fun setUp() {
        telegramClient = mockk()
        entityResolver = mockk()
        guardrailService = mockk(relaxed = true)
        auditService = mockk(relaxed = true)
        objectMapper = jacksonObjectMapper().findAndRegisterModules()
        exchange = mockk(relaxed = true)

        tool = GetRecentActionsTool(
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
        assertEquals("get_recent_actions", tool.definition().name())
    }

    @Test
    fun `returns recent actions successfully`() {
        val events = listOf(
            AdminActionInfo(1L, Instant.now(), 99L, "Admin", "TitleChanged", "'old' → 'new'"),
        )
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { telegramClient.getChatEventLog(42L, 50) } returns events

        val result = tool.execute(exchange, mapOf("chat_id" to 42))

        assertFalse(result.isError)
        verify { guardrailService.validateChatAccess(42L) }
    }

    @Test
    fun `returns error when chat_id is missing`() {
        val result = tool.execute(exchange, emptyMap())
        assertTrue(result.isError)
    }
}
