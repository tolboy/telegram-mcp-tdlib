package dev.telegrammcp.server.tool.chat

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.model.ChatMember
import dev.telegrammcp.server.model.MemberStatus
import dev.telegrammcp.server.service.EntityResolverService
import dev.telegrammcp.server.service.GuardrailService
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

class GetAdminsToolTest {

    private lateinit var telegramClient: TelegramClientService
    private lateinit var entityResolver: EntityResolverService
    private lateinit var guardrailService: GuardrailService
    private lateinit var objectMapper: ObjectMapper
    private lateinit var tool: GetAdminsTool
    private lateinit var exchange: McpSyncServerExchange

    @BeforeEach
    fun setUp() {
        telegramClient = mockk()
        entityResolver = mockk()
        guardrailService = mockk(relaxed = true)
        objectMapper = jacksonObjectMapper().findAndRegisterModules()
        exchange = mockk(relaxed = true)

        tool = GetAdminsTool(
            telegramClient = telegramClient,
            entityResolver = entityResolver,
            guardrailService = guardrailService,
            objectMapper = objectMapper,
            meterRegistry = SimpleMeterRegistry(),
        )
    }

    @Test
    fun `definition returns correct tool name`() {
        assertEquals("get_admins", tool.definition().name())
    }

    @Test
    fun `returns admin list on success`() {
        val admins = listOf(ChatMember(userId = 1L, firstName = "Admin", status = MemberStatus.ADMIN))
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { telegramClient.getChatAdmins(42L, 50) } returns admins

        val result = tool.execute(exchange, mapOf("chat_id" to 42))

        assertFalse(result.isError)
        verify { guardrailService.validateChatAccess(42L) }
        verify { telegramClient.getChatAdmins(42L, 50) }
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("Admin"))
    }

    @Test
    fun `passes custom limit to client`() {
        val admins = listOf(ChatMember(userId = 2L, firstName = "Creator", status = MemberStatus.CREATOR))
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { telegramClient.getChatAdmins(42L, 10) } returns admins

        val result = tool.execute(exchange, mapOf("chat_id" to 42, "limit" to 10))

        assertFalse(result.isError)
        verify { telegramClient.getChatAdmins(42L, 10) }
    }

    @Test
    fun `returns error when chat_id is missing`() {
        val result = tool.execute(exchange, emptyMap())

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("chat_id is required"))
    }
}
