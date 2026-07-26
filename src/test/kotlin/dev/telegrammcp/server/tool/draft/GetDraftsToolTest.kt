package dev.telegrammcp.server.tool.draft

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.model.DraftInfo
import dev.telegrammcp.server.service.AuditService
import dev.telegrammcp.server.service.GuardrailService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.modelcontextprotocol.server.McpSyncServerExchange
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class GetDraftsToolTest {

    private lateinit var telegramClient: TelegramClientService
    private lateinit var guardrailService: GuardrailService
    private lateinit var auditService: AuditService
    private lateinit var objectMapper: ObjectMapper
    private lateinit var tool: GetDraftsTool
    private lateinit var exchange: McpSyncServerExchange

    @BeforeEach
    fun setUp() {
        telegramClient = mockk()
        guardrailService = mockk(relaxed = true)
        every { guardrailService.isChatAllowed(any()) } returns true
        auditService = mockk(relaxed = true)
        objectMapper = jacksonObjectMapper().findAndRegisterModules()
        exchange = mockk(relaxed = true)

        tool = GetDraftsTool(
            telegramClient = telegramClient,
            guardrailService = guardrailService,
            auditService = auditService,
            objectMapper = objectMapper,
            meterRegistry = SimpleMeterRegistry(),
        )
    }

    @Test
    fun `definition returns correct tool name`() {
        assertEquals("get_drafts", tool.definition().name())
    }

    @Test
    fun `returns drafts successfully`() {
        val drafts = listOf(
            DraftInfo(42L, "Test Chat", "Draft text", null, Instant.now()),
        )
        every { telegramClient.getDrafts() } returns drafts

        val result = tool.execute(exchange, emptyMap())

        assertFalse(result.isError)
    }

    @Test
    fun `returns empty list when no drafts`() {
        every { telegramClient.getDrafts() } returns emptyList()

        val result = tool.execute(exchange, emptyMap())

        assertFalse(result.isError)
    }

    @Test
    fun `omits drafts outside the configured allow-list`() {
        every { telegramClient.getDrafts() } returns listOf(
            DraftInfo(42L, "Allowed", "allowed draft", null, Instant.now()),
            DraftInfo(99L, "Private", "private draft", null, Instant.now()),
        )
        every { guardrailService.isChatAllowed(42L) } returns true
        every { guardrailService.isChatAllowed(99L) } returns false

        val result = tool.execute(exchange, emptyMap())

        assertFalse(result.isError)
        val text = (result.content.first() as io.modelcontextprotocol.spec.McpSchema.TextContent).text()
        kotlin.test.assertTrue(text.contains("allowed draft"))
        kotlin.test.assertFalse(text.contains("private draft"))
    }
}
