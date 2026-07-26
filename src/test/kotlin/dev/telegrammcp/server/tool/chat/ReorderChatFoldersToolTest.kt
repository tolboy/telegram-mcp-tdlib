package dev.telegrammcp.server.tool.chat

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.model.ChatFolderDefinition
import dev.telegrammcp.server.model.ChatFolderDetails
import dev.telegrammcp.server.service.AuditService
import dev.telegrammcp.server.service.GuardrailService
import dev.telegrammcp.server.service.OperationGuardService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.modelcontextprotocol.server.McpSyncServerExchange
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReorderChatFoldersToolTest {

    private lateinit var telegramClient: TelegramClientService
    private lateinit var guardrailService: GuardrailService
    private lateinit var operationGuardService: OperationGuardService
    private lateinit var auditService: AuditService
    private lateinit var objectMapper: ObjectMapper
    private lateinit var tool: ReorderChatFoldersTool
    private lateinit var exchange: McpSyncServerExchange

    @BeforeEach
    fun setUp() {
        telegramClient = mockk()
        guardrailService = mockk(relaxed = true)
        operationGuardService = mockk(relaxed = true)
        auditService = mockk(relaxed = true)
        objectMapper = jacksonObjectMapper().findAndRegisterModules()
        exchange = mockk(relaxed = true)
        every {
            telegramClient.getChatFolder(any())
        } answers {
            val folderId = firstArg<Int>()
            ChatFolderDetails(folderId, ChatFolderDefinition(title = "Folder $folderId"))
        }

        tool = ReorderChatFoldersTool(
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
        assertEquals("reorder_chat_folders", tool.definition().name())
    }

    @Test
    fun `reorders folders`() {
        every { telegramClient.reorderChatFolders(listOf(3, 1, 2), 0) } returns true

        val result = tool.execute(exchange, mapOf("folder_ids" to listOf(3, 1, 2)))

        assertFalse(result.isError)
        verify { telegramClient.reorderChatFolders(listOf(3, 1, 2), 0) }
    }

    @Test
    fun `passes main list position`() {
        every { telegramClient.reorderChatFolders(listOf(1, 2), 1) } returns true

        val result = tool.execute(exchange, mapOf("folder_ids" to listOf(1, 2), "main_list_position" to 1))

        assertFalse(result.isError)
        verify { telegramClient.reorderChatFolders(listOf(1, 2), 1) }
    }

    @Test
    fun `validates every folder before reordering`() {
        every {
            telegramClient.getChatFolder(3)
        } returns ChatFolderDetails(3, ChatFolderDefinition(title = "Work", includedChatIds = listOf(42L)))
        every { telegramClient.reorderChatFolders(listOf(3), 0) } returns true

        val result = tool.execute(exchange, mapOf("folder_ids" to listOf(3)))

        assertFalse(result.isError)
        verify { guardrailService.validateDerivedChatAccess(42L) }
    }

    @Test
    fun `rejects empty folder list`() {
        val result = tool.execute(exchange, mapOf("folder_ids" to emptyList<Int>()))

        assertTrue(result.isError)
        verify(exactly = 0) { telegramClient.reorderChatFolders(any(), any()) }
    }

    @Test
    fun `rejects non-positive folder ids`() {
        val result = tool.execute(exchange, mapOf("folder_ids" to listOf(0, 1)))

        assertTrue(result.isError)
        verify(exactly = 0) { telegramClient.reorderChatFolders(any(), any()) }
    }
}
