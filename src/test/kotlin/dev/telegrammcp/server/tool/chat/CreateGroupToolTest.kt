package dev.telegrammcp.server.tool.chat

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.model.ChatInfo
import dev.telegrammcp.server.model.ChatType
import dev.telegrammcp.server.service.AuditService
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

class CreateGroupToolTest {

    private lateinit var telegramClient: TelegramClientService
    private lateinit var operationGuardService: OperationGuardService
    private lateinit var auditService: AuditService
    private lateinit var objectMapper: ObjectMapper
    private lateinit var tool: CreateGroupTool
    private lateinit var exchange: McpSyncServerExchange

    @BeforeEach
    fun setUp() {
        telegramClient = mockk()
        operationGuardService = mockk(relaxed = true)
        auditService = mockk(relaxed = true)
        objectMapper = jacksonObjectMapper().findAndRegisterModules()
        exchange = mockk(relaxed = true)

        tool = CreateGroupTool(
            telegramClient = telegramClient,
            operationGuardService = operationGuardService,
            auditService = auditService,
            objectMapper = objectMapper,
            meterRegistry = SimpleMeterRegistry(),
        )
    }

    @Test
    fun `definition returns correct tool name`() {
        assertEquals("create_group", tool.definition().name())
    }

    @Test
    fun `creates group successfully`() {
        val chatInfo = ChatInfo(chatId = 100, title = "New Group", type = ChatType.GROUP)
        every { telegramClient.createBasicGroup("New Group", listOf(1L, 2L)) } returns chatInfo

        val result = tool.execute(
            exchange,
            mapOf("title" to "New Group", "user_ids" to listOf(1, 2)),
        )

        assertFalse(result.isError)
        verify { operationGuardService.checkPermission("create_group", any()) }
    }

    @Test
    fun `returns error when title is missing`() {
        val result = tool.execute(exchange, mapOf("user_ids" to listOf(1)))

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("title is required"))
    }

    @Test
    fun `returns error when title is blank`() {
        val result = tool.execute(exchange, mapOf("title" to " ", "user_ids" to listOf(1)))

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("title must not be blank"))
    }

    @Test
    fun `returns error when user_ids is empty`() {
        val result = tool.execute(
            exchange,
            mapOf("title" to "Test", "user_ids" to emptyList<Int>()),
        )

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("must not be empty"))
    }
}


