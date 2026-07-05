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
import io.modelcontextprotocol.spec.McpSchema
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ForumTopicAdminToolsTest {

    private lateinit var telegramClient: TelegramClientService
    private lateinit var entityResolver: EntityResolverService
    private lateinit var guardrailService: GuardrailService
    private lateinit var operationGuardService: OperationGuardService
    private lateinit var auditService: AuditService
    private lateinit var objectMapper: ObjectMapper
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
    }

    @Test
    fun `edit_forum_topic renames topic`() {
        val tool = editTool()
        every { entityResolver.resolve(42 as Any) } returns 42L
        every {
            telegramClient.editForumTopic(42L, 1001L, "Renamed", false, null)
        } returns true

        val args = mapOf("chat_id" to 42, "message_thread_id" to 1001, "name" to "Renamed")
        val result = tool.execute(exchange, args)

        assertFalse(result.isError)
        assertEquals("edit_forum_topic", tool.definition().name())
        verify { operationGuardService.checkPermission("edit_forum_topic", args) }
        verify { guardrailService.validateInput("Renamed") }
        verify { guardrailService.validateChatAccess(42L) }
        verify { telegramClient.editForumTopic(42L, 1001L, "Renamed", false, null) }
    }

    @Test
    fun `edit_forum_topic accepts title and topic_id aliases`() {
        val tool = editTool()
        every { entityResolver.resolve("@forum" as Any) } returns 42L
        every {
            telegramClient.editForumTopic(42L, 1002L, "Alias title", false, null)
        } returns true

        val result = tool.execute(exchange, mapOf("chat_id" to "@forum", "topic_id" to "1002", "title" to "Alias title"))

        assertFalse(result.isError)
        verify { telegramClient.editForumTopic(42L, 1002L, "Alias title", false, null) }
    }

    @Test
    fun `edit_forum_topic returns error without mutation`() {
        val tool = editTool()
        every { entityResolver.resolve(42 as Any) } returns 42L

        val result = tool.execute(exchange, mapOf("chat_id" to 42, "message_thread_id" to 1001))

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("name/title or icon update is required"))
    }

    @Test
    fun `close_forum_topic closes topic`() {
        val tool = closeTool()
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { telegramClient.closeForumTopic(42L, 1001L) } returns true

        val args = mapOf("chat_id" to 42, "message_thread_id" to 1001)
        val result = tool.execute(exchange, args)

        assertFalse(result.isError)
        assertEquals("close_forum_topic", tool.definition().name())
        verify { operationGuardService.checkPermission("close_forum_topic", args) }
        verify { guardrailService.validateChatAccess(42L) }
        verify { telegramClient.closeForumTopic(42L, 1001L) }
    }

    @Test
    fun `reopen_forum_topic reopens topic`() {
        val tool = reopenTool()
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { telegramClient.reopenForumTopic(42L, 1001L) } returns true

        val args = mapOf("chat_id" to 42, "thread_id" to 1001)
        val result = tool.execute(exchange, args)

        assertFalse(result.isError)
        assertEquals("reopen_forum_topic", tool.definition().name())
        verify { operationGuardService.checkPermission("reopen_forum_topic", args) }
        verify { guardrailService.validateChatAccess(42L) }
        verify { telegramClient.reopenForumTopic(42L, 1001L) }
    }

    private fun editTool() = EditForumTopicTool(
        telegramClient = telegramClient,
        entityResolver = entityResolver,
        guardrailService = guardrailService,
        operationGuardService = operationGuardService,
        auditService = auditService,
        objectMapper = objectMapper,
        meterRegistry = SimpleMeterRegistry(),
    )

    private fun closeTool() = CloseForumTopicTool(
        telegramClient = telegramClient,
        entityResolver = entityResolver,
        guardrailService = guardrailService,
        operationGuardService = operationGuardService,
        auditService = auditService,
        objectMapper = objectMapper,
        meterRegistry = SimpleMeterRegistry(),
    )

    private fun reopenTool() = ReopenForumTopicTool(
        telegramClient = telegramClient,
        entityResolver = entityResolver,
        guardrailService = guardrailService,
        operationGuardService = operationGuardService,
        auditService = auditService,
        objectMapper = objectMapper,
        meterRegistry = SimpleMeterRegistry(),
    )
}
