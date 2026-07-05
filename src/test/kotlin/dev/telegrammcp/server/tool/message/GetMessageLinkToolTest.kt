package dev.telegrammcp.server.tool.message

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.model.TelegramMessageLink
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GetMessageLinkToolTest {

    private lateinit var telegramClient: TelegramClientService
    private lateinit var entityResolver: EntityResolverService
    private lateinit var guardrailService: GuardrailService
    private lateinit var tool: GetMessageLinkTool
    private lateinit var exchange: McpSyncServerExchange

    @BeforeEach
    fun setUp() {
        telegramClient = mockk()
        entityResolver = mockk()
        guardrailService = mockk(relaxed = true)
        val objectMapper: ObjectMapper = jacksonObjectMapper().findAndRegisterModules()
        exchange = mockk(relaxed = true)
        tool = GetMessageLinkTool(
            telegramClient = telegramClient,
            entityResolver = entityResolver,
            guardrailService = guardrailService,
            auditService = mockk(relaxed = true),
            objectMapper = objectMapper,
            meterRegistry = SimpleMeterRegistry(),
        )
    }

    @Test
    fun `definition returns correct tool name`() {
        assertEquals("get_message_link", tool.definition().name())
    }

    @Test
    fun `creates a message link with optional album and thread flags`() {
        every { entityResolver.resolve("@channel" as Any) } returns 42L
        every { telegramClient.getMessageLink(42L, 99L, true, true) } returns
            TelegramMessageLink(42L, 99L, "https://t.me/channel/99", true)

        val result = tool.execute(
            exchange,
            mapOf(
                "chat_id" to "@channel",
                "message_id" to 99,
                "for_album" to true,
                "in_message_thread" to "true",
            ),
        )

        assertFalse(result.isError)
        verify { guardrailService.validateChatAccess(42L) }
        verify { telegramClient.getMessageLink(42L, 99L, true, true) }
    }

    @Test
    fun `returns error for invalid boolean flag`() {
        every { entityResolver.resolve(42 as Any) } returns 42L

        val result = tool.execute(
            exchange,
            mapOf("chat_id" to 42, "message_id" to 99, "for_album" to "yes"),
        )

        assertTrue(result.isError)
    }
}
