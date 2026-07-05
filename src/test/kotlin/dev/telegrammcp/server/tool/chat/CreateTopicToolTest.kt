package dev.telegrammcp.server.tool.chat

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.model.ForumTopicInfoModel
import dev.telegrammcp.server.service.AuditService
import dev.telegrammcp.server.service.EntityResolverService
import dev.telegrammcp.server.service.GuardrailService
import dev.telegrammcp.server.service.OperationGuardService
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

class CreateTopicToolTest {

    private lateinit var telegramClient: TelegramClientService
    private lateinit var entityResolver: EntityResolverService
    private lateinit var guardrailService: GuardrailService
    private lateinit var operationGuardService: OperationGuardService
    private lateinit var auditService: AuditService
    private lateinit var objectMapper: ObjectMapper
    private lateinit var tool: CreateTopicTool
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

        tool = CreateTopicTool(
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
        assertEquals("create_topic", tool.definition().name())
    }

    @Test
    fun `creates topic in resolved chat`() {
        val topic = ForumTopicInfoModel(
            chatId = 42L,
            topicId = 1001L,
            messageThreadId = 1001L,
            name = "Memory",
            isGeneral = false,
            isClosed = false,
            isPinned = false,
            unreadCount = 0,
            creationDate = null,
            iconColor = 0x6FB9F0,
        )
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { telegramClient.createForumTopic(42L, "Memory", null, null) } returns topic

        val result = tool.execute(exchange, mapOf("chat_id" to 42, "name" to "Memory"))

        assertFalse(result.isError)
        verify { operationGuardService.checkPermission("create_topic", mapOf("chat_id" to 42, "name" to "Memory")) }
        verify { guardrailService.validateInput("Memory") }
        verify { guardrailService.validateChatAccess(42L) }
        verify { telegramClient.createForumTopic(42L, "Memory", null, null) }
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("Memory"))
    }

    @Test
    fun `passes optional icon color and custom emoji id`() {
        val topic = ForumTopicInfoModel(
            chatId = 42L,
            topicId = 1002L,
            messageThreadId = 1002L,
            name = "Pinned Notes",
            isGeneral = false,
            isClosed = false,
            isPinned = false,
            unreadCount = 0,
            creationDate = null,
            iconColor = 0xFFD67E,
            customEmojiId = 777L,
        )
        every { entityResolver.resolve("@forum" as Any) } returns 42L
        every { telegramClient.createForumTopic(42L, "Pinned Notes", 0xFFD67E, 777L) } returns topic

        val result = tool.execute(
            exchange,
            mapOf(
                "chat_id" to "@forum",
                "name" to "Pinned Notes",
                "icon_color" to "0xFFD67E",
                "custom_emoji_id" to "777",
            ),
        )

        assertFalse(result.isError)
        verify { telegramClient.createForumTopic(42L, "Pinned Notes", 0xFFD67E, 777L) }
    }

    @Test
    fun `returns error when name is missing`() {
        val result = tool.execute(exchange, mapOf("chat_id" to 42))

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("name is required"))
    }

    @Test
    fun `returns error when icon color is invalid`() {
        every { entityResolver.resolve(42 as Any) } returns 42L

        val result = tool.execute(
            exchange,
            mapOf("chat_id" to 42, "name" to "Memory", "icon_color" to "blue"),
        )

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("icon_color"))
    }
}
