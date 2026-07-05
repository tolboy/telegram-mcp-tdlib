package dev.telegrammcp.server.tool.chat

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.model.ChatFolderDefinition
import dev.telegrammcp.server.model.ChatFolderInfo
import dev.telegrammcp.server.service.AuditService
import dev.telegrammcp.server.service.EntityResolverService
import dev.telegrammcp.server.service.GuardrailService
import dev.telegrammcp.server.service.OperationGuardService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.modelcontextprotocol.server.McpSyncServerExchange
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse

class ChatFolderToolsTest {

    private val telegramClient = mockk<TelegramClientService>()
    private val entityResolver = mockk<EntityResolverService>()
    private val guardrails = mockk<GuardrailService>(relaxed = true)
    private val operationGuard = mockk<OperationGuardService>(relaxed = true)
    private val audit = mockk<AuditService>(relaxed = true)
    private val exchange = mockk<McpSyncServerExchange>(relaxed = true)
    private val mapper = jacksonObjectMapper().findAndRegisterModules()

    @Test
    fun `creates a folder after resolving and authorizing its chats`() {
        val tool = ConfigureChatFolderTool(telegramClient, entityResolver, guardrails, operationGuard, audit, mapper, SimpleMeterRegistry())
        every { entityResolver.resolve("@included" as Any) } returns 11L
        every { entityResolver.resolve(12 as Any) } returns 12L
        every { telegramClient.createChatFolder(any()) } returns ChatFolderInfo(3, "Work")

        val result = tool.execute(
            exchange,
            mapOf(
                "title" to "Work",
                "included_chats" to listOf("@included"),
                "pinned_chats" to listOf(12),
                "include_groups" to true,
            ),
        )

        assertFalse(result.isError)
        verify { operationGuard.checkPermission("configure_chat_folder", any()) }
        verify { guardrails.validateChatAccess(11L) }
        verify { guardrails.validateChatAccess(12L) }
        verify {
            telegramClient.createChatFolder(
                ChatFolderDefinition(
                    title = "Work",
                    pinnedChatIds = listOf(12L),
                    includedChatIds = listOf(11L),
                    includeGroups = true,
                ),
            )
        }
    }

    @Test
    fun `deleting a folder delegates through the destructive operation guard`() {
        val tool = DeleteChatFolderTool(telegramClient, operationGuard, audit, mapper, SimpleMeterRegistry())
        every { telegramClient.deleteChatFolder(3) } returns true

        val result = tool.execute(exchange, mapOf("folder_id" to 3, "confirmed" to true))

        assertFalse(result.isError)
        verify { operationGuard.checkPermission("delete_chat_folder", any()) }
        verify { telegramClient.deleteChatFolder(3) }
    }
}
