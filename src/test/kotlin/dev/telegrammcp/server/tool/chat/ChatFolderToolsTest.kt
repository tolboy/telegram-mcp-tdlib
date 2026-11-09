package dev.telegrammcp.server.tool.chat

import dev.telegrammcp.server.tool.executeChecked

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.config.McpSecurityProperties
import dev.telegrammcp.server.config.TelegramProperties
import dev.telegrammcp.server.model.ChatFolderDefinition
import dev.telegrammcp.server.model.ChatFolderDetails
import dev.telegrammcp.server.model.ChatFolderInfo
import dev.telegrammcp.server.model.ChatFolderListing
import dev.telegrammcp.server.service.AuditService
import dev.telegrammcp.server.service.EntityResolverService
import dev.telegrammcp.server.service.GuardrailService
import dev.telegrammcp.server.service.OperationGuardService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.modelcontextprotocol.server.McpSyncServerExchange
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatFolderToolsTest {

    private val telegramClient = mockk<TelegramClientService>()
    private val entityResolver = mockk<EntityResolverService>()
    private val guardrails = mockk<GuardrailService>(relaxed = true)
    private val operationGuard = mockk<OperationGuardService>(relaxed = true)
    private val audit = mockk<AuditService>(relaxed = true)
    private val exchange = mockk<McpSyncServerExchange>(relaxed = true)
    private val mapper = jacksonObjectMapper().findAndRegisterModules()

    @Test
    fun `list folders omits entries whose membership exceeds the static chat allow-list`() {
        val tool = ListChatFoldersTool(telegramClient, guardrails, audit, mapper, SimpleMeterRegistry())
        every { guardrails.hasChatAllowList() } returns true
        every { guardrails.isChatAllowed(11L) } returns true
        every { guardrails.isChatAllowed(99L) } returns false
        every {
            telegramClient.listChatFolders()
        } returns ChatFolderListing(
            folders = listOf(
                ChatFolderInfo(1, "Allowed"),
                ChatFolderInfo(2, "Forbidden sentinel"),
            ),
            initialized = true,
        )
        every {
            telegramClient.getChatFolder(1)
        } returns ChatFolderDetails(1, ChatFolderDefinition(title = "Allowed", includedChatIds = listOf(11L)))
        every {
            telegramClient.getChatFolder(2)
        } returns ChatFolderDetails(2, ChatFolderDefinition(title = "Forbidden sentinel", includedChatIds = listOf(99L)))

        val result = tool.executeChecked(exchange, emptyMap())

        assertFalse(result.isError)
        val text = (result.content.first() as io.modelcontextprotocol.spec.McpSchema.TextContent).text()
        assertTrue(text.contains("Allowed"))
        assertFalse(text.contains("Forbidden sentinel"))
    }

    @Test
    fun `creates a folder after resolving and authorizing its chats`() {
        val tool = ConfigureChatFolderTool(telegramClient, entityResolver, guardrails, operationGuard, audit, mapper, SimpleMeterRegistry())
        every { entityResolver.resolve("@included" as Any) } returns 11L
        every { entityResolver.resolve(12 as Any) } returns 12L
        every { telegramClient.createChatFolder(any()) } returns ChatFolderInfo(3, "Work")

        val result = tool.executeChecked(
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
    @Suppress("UNCHECKED_CAST")
    fun `explicit null folder flag is invalid and omission still means false`() {
        val tool = ConfigureChatFolderTool(telegramClient, entityResolver, guardrails, operationGuard, audit, mapper, SimpleMeterRegistry())
        every { telegramClient.createChatFolder(any()) } returns ChatFolderInfo(3, "Work")
        val rejected = (mapOf("title" to "Work", "is_shareable" to null) as Map<String, Any>)
        val invalid = tool.executeChecked(exchange, rejected)
        assertTrue(invalid.isError)
        val created = slot<ChatFolderDefinition>()
        every { telegramClient.createChatFolder(capture(created)) } returns ChatFolderInfo(3, "Work")
        val omitted = tool.executeChecked(exchange, mapOf("title" to "Work"))
        assertFalse(omitted.isError)
        assertEquals(false, created.captured.isShareable)
        verify(exactly = 1) { telegramClient.createChatFolder(any()) }
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `supplied null folder membership cannot silently become an empty replacement`() {
        val tool = ConfigureChatFolderTool(telegramClient, entityResolver, guardrails, operationGuard, audit, mapper, SimpleMeterRegistry())
        every { telegramClient.createChatFolder(any()) } returns ChatFolderInfo(3, "Work")
        every { telegramClient.updateChatFolder(any(), any()) } returns ChatFolderInfo(3, "Work")
        for (field in listOf("pinned_chats", "included_chats", "excluded_chats")) {
            for (folder in listOf(emptyMap(), mapOf("folder_id" to 3))) {
                val arguments = (mapOf("title" to "Work", field to null) + folder) as Map<String, Any>
                val result = tool.executeChecked(exchange, arguments)
                assertTrue(result.isError, "$field=null must reject both create and update")
                val error = ((result.structuredContent() as Map<*, *>)["data"] as Map<*, *>)["error"] as Map<*, *>
                kotlin.test.assertEquals("INVALID_INPUT", error["code"])
            }
        }
        verify(exactly = 0) { telegramClient.createChatFolder(any()) }
        verify(exactly = 0) { telegramClient.updateChatFolder(any(), any()) }
    }

    @Test
    fun `invalid interior folder identifiers reject the complete membership list`() {
        val tool = ConfigureChatFolderTool(telegramClient, entityResolver, guardrails, operationGuard, audit, mapper, SimpleMeterRegistry())
        every { entityResolver.resolve("@included" as Any) } returns 11L
        every { telegramClient.createChatFolder(any()) } returns ChatFolderInfo(3, "Work")
        for (field in listOf("pinned_chats", "included_chats", "excluded_chats")) {
            val result = tool.executeChecked(exchange, mapOf("title" to "Work", field to listOf("@included", null)))
            assertTrue(result.isError)
            val error = ((result.structuredContent() as Map<*, *>)["data"] as Map<*, *>)["error"] as Map<*, *>
            kotlin.test.assertEquals("INVALID_INPUT", error["code"])
        }
        verify(exactly = 0) { telegramClient.createChatFolder(any()) }
    }

    @Test
    fun `deleting a folder delegates through the destructive operation guard`() {
        val tool = DeleteChatFolderTool(telegramClient, guardrails, operationGuard, audit, mapper, SimpleMeterRegistry())
        every {
            telegramClient.getChatFolder(3)
        } returns ChatFolderDetails(3, ChatFolderDefinition(title = "Work", includedChatIds = listOf(11L)))
        every { telegramClient.deleteChatFolder(3) } returns true

        val result = tool.executeChecked(exchange, mapOf("folder_id" to 3, "confirmed" to true))

        assertFalse(result.isError)
        verify { operationGuard.checkPermission("delete_chat_folder", any()) }
        verify { guardrails.validateDerivedChatAccess(11L) }
        verify { telegramClient.deleteChatFolder(3) }
    }

    @Test
    fun `blocks dynamic folder membership when a static chat allow-list is configured`() {
        val tool = ConfigureChatFolderTool(telegramClient, entityResolver, guardrails, operationGuard, audit, mapper, SimpleMeterRegistry())
        every { guardrails.hasChatAllowList() } returns true

        val result = tool.executeChecked(
            exchange,
            mapOf(
                "title" to "All groups",
                "include_groups" to true,
            ),
        )

        assertTrue(result.isError)
        verify(exactly = 0) { telegramClient.createChatFolder(any()) }
    }

    @Test
    fun `a mutation-only chat list blocks folder rules with unknown target chats`() {
        val scoped = GuardrailService(
            McpSecurityProperties(),
            TelegramProperties(security = TelegramProperties.SecurityProperties(mutationAllowedChatIds = listOf(11L))),
        )
        val tool = ConfigureChatFolderTool(telegramClient, entityResolver, scoped, operationGuard, audit, mapper, SimpleMeterRegistry())
        val result = scoped.mutationScope("configure_chat_folder").use {
            tool.executeChecked(exchange, mapOf("title" to "All groups", "include_groups" to true))
        }
        assertTrue(result.isError)
        verify(exactly = 0) { telegramClient.createChatFolder(any()) }
    }
}
