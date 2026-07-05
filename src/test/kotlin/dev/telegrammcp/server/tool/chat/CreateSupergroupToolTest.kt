package dev.telegrammcp.server.tool.chat

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.model.ChatInfo
import dev.telegrammcp.server.model.ChatType
import dev.telegrammcp.server.service.AuditService
import dev.telegrammcp.server.service.FileSecurityService
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CreateSupergroupToolTest {

    private lateinit var telegramClient: TelegramClientService
    private lateinit var guardrailService: GuardrailService
    private lateinit var operationGuardService: OperationGuardService
    private lateinit var fileSecurityService: FileSecurityService
    private lateinit var auditService: AuditService
    private lateinit var objectMapper: ObjectMapper
    private lateinit var tool: CreateSupergroupTool
    private lateinit var exchange: McpSyncServerExchange

    @BeforeEach
    fun setUp() {
        telegramClient = mockk()
        guardrailService = mockk(relaxed = true)
        operationGuardService = mockk(relaxed = true)
        fileSecurityService = mockk(relaxed = true)
        auditService = mockk(relaxed = true)
        objectMapper = jacksonObjectMapper().findAndRegisterModules()
        exchange = mockk(relaxed = true)

        tool = CreateSupergroupTool(
            telegramClient = telegramClient,
            guardrailService = guardrailService,
            operationGuardService = operationGuardService,
            fileSecurityService = fileSecurityService,
            auditService = auditService,
            objectMapper = objectMapper,
            meterRegistry = SimpleMeterRegistry(),
        )
    }

    @Test
    fun `fails before side effects when Telegram returns a channel`() {
        every {
            telegramClient.createSupergroupOrChannel("Community Updates", "", true, true)
        } returns ChatInfo(chatId = -1001L, title = "Community Updates", type = ChatType.CHANNEL)

        val result = tool.execute(
            exchange,
            mapOf("title" to "Community Updates", "confirmed" to true),
        )

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("expected SUPERGROUP"), "Unexpected error text: $text")
        verify(exactly = 0) { telegramClient.setForumTopicsEnabled(any(), any(), any()) }
        verify(exactly = 0) { telegramClient.setChatPhoto(any(), any()) }
        verify(exactly = 0) { telegramClient.createForumTopic(any(), any(), any(), any()) }
    }

    @Test
    fun `creates supergroup without duplicate forum toggle`() {
        every {
            telegramClient.createSupergroupOrChannel("Community Updates", "", true, true)
        } returns ChatInfo(chatId = -1002L, title = "Community Updates", type = ChatType.SUPERGROUP)

        val result = tool.execute(
            exchange,
            mapOf("title" to "Community Updates", "confirmed" to true),
        )

        assertFalse(result.isError)
        verify(exactly = 0) { telegramClient.setForumTopicsEnabled(any(), any(), any()) }
    }
}
