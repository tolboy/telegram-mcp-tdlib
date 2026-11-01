package dev.telegrammcp.server.tool.chat

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.model.ChatJoinRequestInfo
import dev.telegrammcp.server.model.ChatJoinRequestList
import dev.telegrammcp.server.model.ChatStatisticValue
import dev.telegrammcp.server.model.ChatStatisticsSummary
import dev.telegrammcp.server.model.ChatStatisticsTopSender
import dev.telegrammcp.server.service.AuditService
import dev.telegrammcp.server.service.EntityResolverService
import dev.telegrammcp.server.service.GuardrailService
import dev.telegrammcp.server.service.OperationGuardService
import dev.telegrammcp.server.tool.executeChecked
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModerationToolsTest {

    private lateinit var telegramClient: TelegramClientService
    private lateinit var entityResolver: EntityResolverService
    private lateinit var guardrailService: GuardrailService
    private lateinit var operationGuardService: OperationGuardService
    private val auditService = mockk<AuditService>(relaxed = true)
    private val objectMapper = jacksonObjectMapper().findAndRegisterModules()
    private val exchange = mockk<McpSyncServerExchange>(relaxed = true)

    @BeforeEach
    fun setUp() {
        telegramClient = mockk()
        entityResolver = mockk()
        guardrailService = mockk(relaxed = true)
        operationGuardService = mockk(relaxed = true)
        every { entityResolver.resolve(-100 as Any) } returns -100L
    }

    private fun data(result: McpSchema.CallToolResult) = (result.structuredContent() as Map<*, *>)["data"] as Map<*, *>

    private fun list() = ListJoinRequestsTool(telegramClient, entityResolver, guardrailService, auditService,
        objectMapper, SimpleMeterRegistry())
    private fun process() = ProcessJoinRequestTool(telegramClient, entityResolver, guardrailService,
        operationGuardService, auditService, objectMapper, SimpleMeterRegistry())
    private fun deleteBySender() = DeleteMessagesBySenderTool(telegramClient, entityResolver, guardrailService,
        operationGuardService, auditService, objectMapper, SimpleMeterRegistry())
    private fun statistics() = GetChatStatisticsTool(telegramClient, entityResolver, guardrailService, auditService,
        objectMapper, SimpleMeterRegistry())

    @Test
    fun `list_join_requests returns requests and defaults the limit`() {
        every { telegramClient.getChatJoinRequests(-100L, "", "", 50) } returns
            ChatJoinRequestList(-100L, 1, listOf(ChatJoinRequestInfo(7L, Instant.ofEpochSecond(1_700_000_000), "hi")))

        val result = list().executeChecked(exchange, mapOf("chat_id" to -100))

        assertFalse(result.isError)
        assertEquals(1, (data(result)["totalCount"] as Number).toInt())
        verify { guardrailService.validateChatAccess(-100L) }
    }

    @Test
    fun `list_join_requests rejects an out-of-range limit`() {
        val result = list().executeChecked(exchange, mapOf("chat_id" to -100, "limit" to 500))
        assertTrue(result.isError)
        verify(exactly = 0) { telegramClient.getChatJoinRequests(any(), any(), any(), any()) }
    }

    @Test
    fun `process_join_request approves and declines one user`() {
        every { telegramClient.processChatJoinRequest(-100L, 7L, any()) } returns true

        val approved = process().executeChecked(exchange, mapOf("chat_id" to -100, "user_id" to 7, "action" to "approve"))
        val declined = process().executeChecked(exchange, mapOf("chat_id" to -100, "user_id" to "7", "action" to "decline"))

        assertEquals(true, data(approved)["approved"])
        assertEquals(false, data(declined)["approved"])
        verify { telegramClient.processChatJoinRequest(-100L, 7L, true) }
        verify { telegramClient.processChatJoinRequest(-100L, 7L, false) }
        verify(exactly = 2) { operationGuardService.checkPermission("process_join_request", any()) }
    }

    @Test
    fun `process_join_request rejects bad actions and user IDs`() {
        assertTrue(process().executeChecked(exchange, mapOf("chat_id" to -100, "user_id" to 7, "action" to "ban")).isError)
        assertTrue(process().executeChecked(exchange, mapOf("chat_id" to -100, "user_id" to "@bob", "action" to "approve")).isError)
        assertTrue(process().executeChecked(exchange, mapOf("chat_id" to -100, "user_id" to -5, "action" to "approve")).isError)
        verify(exactly = 0) { telegramClient.processChatJoinRequest(any(), any(), any()) }
    }

    @Test
    fun `delete_messages_by_sender is destructive and guarded`() {
        assertTrue(OperationGuardService.annotationsFor("delete_messages_by_sender").destructiveHint())
        every { telegramClient.deleteMessagesBySender(-100L, -2001L) } returns true

        val result = deleteBySender().executeChecked(exchange,
            mapOf("chat_id" to -100, "sender_id" to -2001, "confirmed" to true))

        assertFalse(result.isError)
        assertEquals(true, data(result)["deleted"])
        verify { operationGuardService.checkPermission("delete_messages_by_sender", any()) }
    }

    @Test
    fun `delete_messages_by_sender rejects a zero or fractional sender`() {
        assertTrue(deleteBySender().executeChecked(exchange, mapOf("chat_id" to -100, "sender_id" to 0)).isError)
        assertTrue(deleteBySender().executeChecked(exchange, mapOf("chat_id" to -100, "sender_id" to 1.5)).isError)
        verify(exactly = 0) { telegramClient.deleteMessagesBySender(any(), any()) }
    }

    @Test
    fun `get_chat_statistics returns the summary`() {
        every { telegramClient.getChatStatistics(-100L) } returns ChatStatisticsSummary(
            chatId = -100L,
            type = "supergroup",
            periodStart = Instant.ofEpochSecond(1),
            periodEnd = Instant.ofEpochSecond(2),
            values = listOf(ChatStatisticValue("member_count", 10.0, 8.0, 25.0)),
            topSenders = listOf(ChatStatisticsTopSender(7L, 3, 40)),
        )

        val result = statistics().executeChecked(exchange, mapOf("chat_id" to -100))

        assertFalse(result.isError)
        assertEquals("supergroup", data(result)["type"])
        verify { guardrailService.validateChatAccess(-100L) }
    }

    @Test
    fun `moderation write tools are classified as writes`() {
        listOf("process_join_request", "delete_messages_by_sender").forEach {
            assertTrue(it in OperationGuardService.WRITE_TOOLS, it)
        }
        listOf("list_join_requests", "get_chat_statistics").forEach {
            assertFalse(it in OperationGuardService.WRITE_TOOLS, it)
        }
    }
}
