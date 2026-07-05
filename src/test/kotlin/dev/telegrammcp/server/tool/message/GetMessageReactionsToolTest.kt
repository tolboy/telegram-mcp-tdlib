package dev.telegrammcp.server.tool.message

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.model.MessageReactionSummary
import dev.telegrammcp.server.model.ReactionInfo
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

class GetMessageReactionsToolTest {

    private lateinit var telegramClient: TelegramClientService
    private lateinit var entityResolver: EntityResolverService
    private lateinit var guardrailService: GuardrailService
    private lateinit var objectMapper: ObjectMapper
    private lateinit var tool: GetMessageReactionsTool
    private lateinit var exchange: McpSyncServerExchange

    @BeforeEach
    fun setUp() {
        telegramClient = mockk()
        entityResolver = mockk()
        guardrailService = mockk(relaxed = true)
        objectMapper = jacksonObjectMapper().findAndRegisterModules()
        exchange = mockk(relaxed = true)

        tool = GetMessageReactionsTool(
            telegramClient = telegramClient,
            entityResolver = entityResolver,
            guardrailService = guardrailService,
            objectMapper = objectMapper,
            meterRegistry = SimpleMeterRegistry(),
        )
    }

    @Test
    fun `definition returns correct tool name`() {
        assertEquals("get_message_reactions", tool.definition().name())
    }

    @Test
    fun `returns reaction summary successfully`() {
        val summary = MessageReactionSummary(
            chatId = 42,
            messageId = 100,
            reactions = listOf(
                ReactionInfo(emoji = "👍", senderId = 999L, senderName = "Alice", isOutgoing = false),
            ),
        )
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { telegramClient.getMessageReactions(42L, 100L, 50) } returns summary

        val result = tool.execute(exchange, mapOf("chat_id" to 42, "message_id" to 100))

        assertFalse(result.isError)
        verify { guardrailService.validateChatAccess(42L) }
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("👍"))
    }

    @Test
    fun `uses specified limit`() {
        val summary = MessageReactionSummary(chatId = 42, messageId = 100, reactions = emptyList())
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { telegramClient.getMessageReactions(42L, 100L, 10) } returns summary

        val result = tool.execute(exchange, mapOf("chat_id" to 42, "message_id" to 100, "limit" to 10))

        assertFalse(result.isError)
        verify { telegramClient.getMessageReactions(42L, 100L, 10) }
    }

    @Test
    fun `returns error when message_id is missing`() {
        every { entityResolver.resolve(42 as Any) } returns 42L

        val result = tool.execute(exchange, mapOf("chat_id" to 42))

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("message_id is required"))
    }
}
