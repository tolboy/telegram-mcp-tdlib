package dev.telegrammcp.server.tool.chat

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.model.ChatInfo
import dev.telegrammcp.server.model.ChatType
import dev.telegrammcp.server.service.AuditService
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

class CreateChannelToolTest {

    private lateinit var telegramClient: TelegramClientService
    private lateinit var guardrailService: GuardrailService
    private lateinit var operationGuardService: OperationGuardService
    private lateinit var auditService: AuditService
    private lateinit var objectMapper: ObjectMapper
    private lateinit var tool: CreateChannelTool
    private lateinit var exchange: McpSyncServerExchange

    @BeforeEach
    fun setUp() {
        telegramClient = mockk()
        guardrailService = mockk(relaxed = true)
        operationGuardService = mockk(relaxed = true)
        auditService = mockk(relaxed = true)
        objectMapper = jacksonObjectMapper().findAndRegisterModules()
        exchange = mockk(relaxed = true)

        tool = CreateChannelTool(
            telegramClient = telegramClient,
            guardrailService = guardrailService,
            operationGuardService = operationGuardService,
            auditService = auditService,
            objectMapper = objectMapper,
            meterRegistry = SimpleMeterRegistry(),
        )
    }

    @Test
    fun `definition returns correct tool name`() {
        assertEquals("create_channel", tool.definition().name())
    }

    @Test
    fun `creates channel when is_supergroup is false`() {
        val chat = ChatInfo(chatId = 100L, title = "MyChannel", type = ChatType.CHANNEL)
        every { telegramClient.createSupergroupOrChannel("MyChannel", "", false, false) } returns chat

        val result = tool.execute(
            exchange,
            mapOf("title" to "MyChannel", "confirmed" to true),
        )

        assertFalse(result.isError)
        verify { telegramClient.createSupergroupOrChannel("MyChannel", "", false, false) }
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("MyChannel"))
    }

    @Test
    fun `creates supergroup when is_supergroup is true`() {
        val chat = ChatInfo(chatId = 200L, title = "MySupergroup", type = ChatType.SUPERGROUP)
        every { telegramClient.createSupergroupOrChannel("MySupergroup", "", true, false) } returns chat

        val result = tool.execute(
            exchange,
            mapOf("title" to "MySupergroup", "is_supergroup" to true, "confirmed" to true),
        )

        assertFalse(result.isError)
        verify { telegramClient.createSupergroupOrChannel("MySupergroup", "", true, false) }
    }

    @Test
    fun `creates forum supergroup when is_forum is true`() {
        val chat = ChatInfo(chatId = 300L, title = "MemoryHub", type = ChatType.SUPERGROUP)
        every { telegramClient.createSupergroupOrChannel("MemoryHub", "Topics", true, true) } returns chat

        val result = tool.execute(
            exchange,
            mapOf(
                "title" to "MemoryHub",
                "description" to "Topics",
                "is_supergroup" to true,
                "is_forum" to true,
                "confirmed" to true,
            ),
        )

        assertFalse(result.isError)
        verify { telegramClient.createSupergroupOrChannel("MemoryHub", "Topics", true, true) }
    }

    @Test
    fun `returns error when is_forum is true for channel`() {
        val result = tool.execute(
            exchange,
            mapOf("title" to "NotForum", "is_forum" to true, "confirmed" to true),
        )

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("is_forum"))
    }

    @Test
    fun `returns error when title is missing`() {
        val result = tool.execute(exchange, mapOf("confirmed" to true))

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("title is required"), "Expected 'title is required' in: $text")
    }

    @Test
    fun `returns error when title exceeds max length`() {
        val longTitle = "A".repeat(129)
        val result = tool.execute(exchange, mapOf("title" to longTitle, "confirmed" to true))

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("128") || text.contains("title"), "Expected max-length error in: $text")
    }
}
