package dev.telegrammcp.server.tool.chat

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

class SetSlowModeToolTest {

    private lateinit var telegramClient: TelegramClientService
    private lateinit var entityResolver: EntityResolverService
    private lateinit var guardrailService: GuardrailService
    private lateinit var operationGuardService: OperationGuardService
    private lateinit var auditService: AuditService
    private lateinit var objectMapper: ObjectMapper
    private lateinit var tool: SetSlowModeTool
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

        tool = SetSlowModeTool(
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
        assertEquals("set_slow_mode", tool.definition().name())
    }

    @Test
    fun `sets an allowed slow-mode delay`() {
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { telegramClient.setChatSlowModeDelay(42L, 60) } returns true

        val result = tool.execute(exchange, mapOf("chat_id" to 42, "delay_seconds" to 60, "confirmed" to true))

        assertFalse(result.isError)
        verify { telegramClient.setChatSlowModeDelay(42L, 60) }
    }

    @Test
    fun `disables slow mode with zero`() {
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { telegramClient.setChatSlowModeDelay(42L, 0) } returns true

        val result = tool.execute(exchange, mapOf("chat_id" to 42, "delay_seconds" to 0, "confirmed" to true))

        assertFalse(result.isError)
        verify { telegramClient.setChatSlowModeDelay(42L, 0) }
    }

    @Test
    fun `rejects a delay Telegram does not accept`() {
        every { entityResolver.resolve(42 as Any) } returns 42L

        val result = tool.execute(exchange, mapOf("chat_id" to 42, "delay_seconds" to 45, "confirmed" to true))

        assertTrue(result.isError)
        verify(exactly = 0) { telegramClient.setChatSlowModeDelay(any(), any()) }
    }
}
