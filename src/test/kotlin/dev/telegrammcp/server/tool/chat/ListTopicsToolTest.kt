package dev.telegrammcp.server.tool.chat

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.model.ForumTopicInfoModel
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

class ListTopicsToolTest {

    private lateinit var telegramClient: TelegramClientService
    private lateinit var entityResolver: EntityResolverService
    private lateinit var guardrailService: GuardrailService
    private lateinit var objectMapper: ObjectMapper
    private lateinit var tool: ListTopicsTool
    private lateinit var exchange: McpSyncServerExchange

    private fun topic(id: Long, name: String) = ForumTopicInfoModel(
        chatId = 42L, topicId = id, messageThreadId = id, name = name,
        isGeneral = (id == 1L), isClosed = false, isPinned = false,
        unreadCount = 0, creationDate = null,
    )

    @BeforeEach
    fun setUp() {
        telegramClient = mockk()
        entityResolver = mockk()
        guardrailService = mockk(relaxed = true)
        objectMapper = jacksonObjectMapper().findAndRegisterModules()
        exchange = mockk(relaxed = true)

        tool = ListTopicsTool(
            telegramClient = telegramClient,
            entityResolver = entityResolver,
            guardrailService = guardrailService,
            objectMapper = objectMapper,
            meterRegistry = SimpleMeterRegistry(),
        )
    }

    @Test
    fun `definition returns correct tool name`() {
        assertEquals("list_topics", tool.definition().name())
    }

    @Test
    fun `returns topics for chat`() {
        val topics = listOf(topic(1L, "General"), topic(2L, "Off-topic"))
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { telegramClient.listForumTopics(42L, "", 50) } returns topics

        val result = tool.execute(exchange, mapOf("chat_id" to 42))

        assertFalse(result.isError)
        verify { guardrailService.validateChatAccess(42L) }
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("General"))
    }

    @Test
    fun `passes query to client`() {
        val topics = listOf(topic(2L, "Off-topic"))
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { telegramClient.listForumTopics(42L, "Off", 50) } returns topics

        val result = tool.execute(exchange, mapOf("chat_id" to 42, "query" to "Off"))

        assertFalse(result.isError)
        verify { telegramClient.listForumTopics(42L, "Off", 50) }
    }

    @Test
    fun `returns error when chat_id is missing`() {
        val result = tool.execute(exchange, emptyMap())

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("chat_id is required"))
    }
}
