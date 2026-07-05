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

class GetBannedUsersToolTest {

    private lateinit var telegramClient: TelegramClientService
    private lateinit var entityResolver: EntityResolverService
    private lateinit var guardrailService: GuardrailService
    private lateinit var objectMapper: ObjectMapper
    private lateinit var tool: GetBannedUsersTool
    private lateinit var exchange: McpSyncServerExchange

    @BeforeEach
    fun setUp() {
        telegramClient = mockk()
        entityResolver = mockk()
        guardrailService = mockk(relaxed = true)
        objectMapper = jacksonObjectMapper().findAndRegisterModules()
        exchange = mockk(relaxed = true)

        tool = GetBannedUsersTool(
            telegramClient = telegramClient,
            entityResolver = entityResolver,
            guardrailService = guardrailService,
            objectMapper = objectMapper,
            meterRegistry = SimpleMeterRegistry(),
        )
    }

    @Test
    fun `definition returns correct tool name`() {
        assertEquals("get_banned_users", tool.definition().name())
    }

    @Test
    fun `returns empty list when no banned users`() {
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { telegramClient.getBannedChatMembers(42L, 50) } returns emptyList()

        val result = tool.execute(exchange, mapOf("chat_id" to 42))

        assertFalse(result.isError)
        verify { guardrailService.validateChatAccess(42L) }
        verify { telegramClient.getBannedChatMembers(42L, 50) }
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("[]") || text.contains("empty") || text.contains("no banned"), "Expected empty list representation in: $text")
    }

    @Test
    fun `returns banned user list`() {
        val banned = listOf(ChatMember(userId = 99L, firstName = "Banned", status = MemberStatus.BANNED))
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { telegramClient.getBannedChatMembers(42L, 50) } returns banned

        val result = tool.execute(exchange, mapOf("chat_id" to 42))

        assertFalse(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("Banned"))
    }

    @Test
    fun `passes custom limit to client`() {
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { telegramClient.getBannedChatMembers(42L, 20) } returns emptyList()

        val result = tool.execute(exchange, mapOf("chat_id" to 42, "limit" to 20))

        assertFalse(result.isError)
        verify { telegramClient.getBannedChatMembers(42L, 20) }
    }

    @Test
    fun `returns error when chat_id is missing`() {
        val result = tool.execute(exchange, emptyMap())

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("chat_id is required"))
    }
}
