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

class MuteChatToolTest {

    private lateinit var telegramClient: TelegramClientService
    private lateinit var entityResolver: EntityResolverService
    private lateinit var guardrailService: GuardrailService
    private lateinit var operationGuardService: OperationGuardService
    private lateinit var auditService: AuditService
    private lateinit var objectMapper: ObjectMapper
    private lateinit var tool: MuteChatTool
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

        tool = MuteChatTool(
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
        assertEquals("mute_chat", tool.definition().name())
    }

    @Test
    fun `mutes chat with default duration`() {
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { telegramClient.muteChat(42L, Int.MAX_VALUE) } returns true

        val result = tool.execute(exchange, mapOf("chat_id" to 42))

        assertFalse(result.isError)
        verify { telegramClient.muteChat(42L, Int.MAX_VALUE) }
    }

    @Test
    fun `mutes chat with custom duration`() {
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { telegramClient.muteChat(42L, 3600) } returns true

        val result = tool.execute(exchange, mapOf("chat_id" to 42, "duration" to 3600))

        assertFalse(result.isError)
        verify { telegramClient.muteChat(42L, 3600) }
    }
}

