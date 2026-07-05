package dev.telegrammcp.server.tool.user

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.model.ChatInfo
import dev.telegrammcp.server.model.ChatType
import dev.telegrammcp.server.service.AuditService
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

class GetCommonChatsToolTest {

    private lateinit var telegramClient: TelegramClientService
    private lateinit var entityResolver: EntityResolverService
    private lateinit var guardrailService: GuardrailService
    private lateinit var auditService: AuditService
    private lateinit var objectMapper: ObjectMapper
    private lateinit var tool: GetCommonChatsTool
    private lateinit var exchange: McpSyncServerExchange

    @BeforeEach
    fun setUp() {
        telegramClient = mockk()
        entityResolver = mockk()
        guardrailService = mockk(relaxed = true)
        auditService = mockk(relaxed = true)
        objectMapper = jacksonObjectMapper().findAndRegisterModules()
        exchange = mockk(relaxed = true)

        tool = GetCommonChatsTool(
            telegramClient = telegramClient,
            entityResolver = entityResolver,
            guardrailService = guardrailService,
            auditService = auditService,
            objectMapper = objectMapper,
            meterRegistry = SimpleMeterRegistry(),
        )
    }

    private fun chat(id: Long, title: String) = ChatInfo(chatId = id, title = title, type = ChatType.SUPERGROUP)

    @Test
    fun `definition returns correct tool name`() {
        assertEquals("get_common_chats", tool.definition().name())
    }

    @Test
    fun `lists common chats`() {
        every { entityResolver.resolve("@alice" as Any) } returns 7L
        every { guardrailService.isChatAllowed(any()) } returns true
        every { telegramClient.getGroupsInCommon(7L, 50) } returns listOf(chat(1L, "Team"), chat(2L, "Friends"))

        val result = tool.execute(exchange, mapOf("user_id" to "@alice"))

        assertFalse(result.isError)
        val text = (result.content().first() as McpSchema.TextContent).text()
        assertTrue("Team" in text)
        verify { telegramClient.getGroupsInCommon(7L, 50) }
    }

    @Test
    fun `filters chats outside the allow-list`() {
        every { entityResolver.resolve(7 as Any) } returns 7L
        every { telegramClient.getGroupsInCommon(7L, 50) } returns listOf(chat(1L, "Allowed"), chat(2L, "Hidden"))
        every { guardrailService.isChatAllowed(1L) } returns true
        every { guardrailService.isChatAllowed(2L) } returns false

        val result = tool.execute(exchange, mapOf("user_id" to 7))

        assertFalse(result.isError)
        val text = (result.content().first() as McpSchema.TextContent).text()
        assertTrue("Allowed" in text)
        assertFalse("Hidden" in text)
    }
}
