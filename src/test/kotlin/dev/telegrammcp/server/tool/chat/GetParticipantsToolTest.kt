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

class GetParticipantsToolTest {

    private lateinit var telegramClient: TelegramClientService
    private lateinit var entityResolver: EntityResolverService
    private lateinit var guardrailService: GuardrailService
    private lateinit var objectMapper: ObjectMapper
    private lateinit var tool: GetParticipantsTool
    private lateinit var exchange: McpSyncServerExchange

    @BeforeEach
    fun setUp() {
        telegramClient = mockk()
        entityResolver = mockk()
        guardrailService = mockk(relaxed = true)
        objectMapper = jacksonObjectMapper().findAndRegisterModules()
        exchange = mockk(relaxed = true)

        tool = GetParticipantsTool(
            telegramClient = telegramClient,
            entityResolver = entityResolver,
            guardrailService = guardrailService,
            objectMapper = objectMapper,
            meterRegistry = SimpleMeterRegistry(),
        )
    }

    @Test
    fun `definition returns correct tool name`() {
        val def = tool.definition()
        assertEquals("get_participants", def.name())
    }

    @Test
    fun `returns participants with default parameters`() {
        val members = listOf(
            ChatMember(userId = 1, firstName = "Alice", lastName = "Smith", username = "alice", status = MemberStatus.ADMIN),
            ChatMember(userId = 2, firstName = "Bob", status = MemberStatus.MEMBER),
        )
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { telegramClient.getChatMembers(42L, "", 0, 50) } returns members

        val result = tool.execute(exchange, mapOf("chat_id" to 42))

        assertFalse(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("Alice"))
        assertTrue(text.contains("Bob"))
        verify { guardrailService.validateChatAccess(42L) }
    }

    @Test
    fun `filters participants by query`() {
        val members = listOf(
            ChatMember(userId = 1, firstName = "Alice", status = MemberStatus.MEMBER),
        )
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { telegramClient.getChatMembers(42L, "alice", 0, 50) } returns members

        val result = tool.execute(
            exchange,
            mapOf("chat_id" to 42, "query" to "alice"),
        )

        assertFalse(result.isError)
        verify { telegramClient.getChatMembers(42L, "alice", 0, 50) }
    }

    @Test
    fun `uses custom limit and offset`() {
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { telegramClient.getChatMembers(42L, "", 10, 25) } returns emptyList()

        val result = tool.execute(
            exchange,
            mapOf("chat_id" to 42, "limit" to 25, "offset" to 10),
        )

        assertFalse(result.isError)
        verify { telegramClient.getChatMembers(42L, "", 10, 25) }
    }

    @Test
    fun `clamps limit to max 200`() {
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { telegramClient.getChatMembers(42L, "", 0, 200) } returns emptyList()

        val result = tool.execute(
            exchange,
            mapOf("chat_id" to 42, "limit" to 999),
        )

        assertFalse(result.isError)
        verify { telegramClient.getChatMembers(42L, "", 0, 200) }
    }

    @Test
    fun `returns error when chat_id is missing`() {
        val result = tool.execute(exchange, emptyMap())

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("chat_id is required"))
    }

    @Test
    fun `returns error for invalid limit type`() {
        every { entityResolver.resolve(42 as Any) } returns 42L

        val result = tool.execute(
            exchange,
            mapOf("chat_id" to 42, "limit" to "not_a_number"),
        )

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("limit must be a valid integer"))
    }

    @Test
    fun `returns error for invalid offset type`() {
        every { entityResolver.resolve(42 as Any) } returns 42L

        val result = tool.execute(
            exchange,
            mapOf("chat_id" to 42, "offset" to "not_a_number"),
        )

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("offset must be a valid integer"))
    }

    @Test
    fun `returns error when telegram client throws`() {
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { telegramClient.getChatMembers(42L, "", 0, 50) } throws RuntimeException("Access denied")

        val result = tool.execute(exchange, mapOf("chat_id" to 42))

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("Access denied"))
    }
}

