package dev.telegrammcp.server.security

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.client.*
import dev.telegrammcp.server.config.*
import dev.telegrammcp.server.service.*
import dev.telegrammcp.server.tool.message.GetHistoryTool
import dev.telegrammcp.server.tool.research.SearchMessagesForIntentTool
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.*
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.security.core.context.SecurityContextHolder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class PermissionDispatchTest {
    @TempDir lateinit var directory: Path
    @AfterEach fun cleanup() { SecurityContextHolder.clearContext() }

    @Test
    fun `dispatch and parallel research enforce the same client account and chat grant`() {
        val mapper = jacksonObjectMapper().findAndRegisterModules()
        val file = Files.writeString(directory.resolve("permissions.json"),
            """{"version":1,"grants":[{"client":"researcher","account":"work","actions":["read"],"chat_ids":[42]}]}""")
        val properties = McpSecurityProperties(permissionsFile = file.toString())
        val permissions = AccessPermissionService(properties, PlatformPaths(), mapper)
        val client = mockk<TelegramClientService>()
        every { client.getHistory(any(), any(), any(), any()) } returns emptyList()
        every { client.searchMessages(any(), any(), any(), any()) } returns emptyList()
        val registry = TelegramAccountRegistry().also {
            it.register(TelegramAccountRegistry.AccountHandle("work", client))
        }
        val context = TelegramAccountContext(registry)
        val routed = AccountRoutingTelegramClientService(registry, context).proxy
        val resolver = EntityResolverService(routed, context)
        val guard = GuardrailService(properties, TelegramProperties(), permissions)
        val modes = ServerModeProperties(readOnly = true)
        val metrics = SimpleMeterRegistry()
        val audit = AuditService(modes, metrics, mapper, accountContext = context)
        val history = GetHistoryTool(routed, resolver, guard, mapper, metrics)
        val search = SearchMessagesForIntentTool(routed, resolver, PublicSearchProperties(), guard, audit, mapper, metrics, context)
        val tools = McpConfig().syncToolSpecifications(listOf(history, search), registry, context,
            AccountAccessPolicy(registry, permissions = permissions), modes, ToolSurfacePolicy(properties), audit,
            DestructiveApprovalService(modes, OperationGuardService(modes, mockk(relaxed = true))), permissions)
            .associateBy { it.tool().name() }
        val exchange = mockk<McpSyncServerExchange>(relaxed = true)
        fun call(name: String, arguments: Map<String, Any>) = tools.getValue(name).callHandler().apply(exchange,
            McpSchema.CallToolRequest(name, arguments, emptyMap()))
        SecurityContextHolder.getContext().authentication = ApiKeyAuthToken("researcher", setOf("work"))
        assertFalse(call("get_history", mapOf("chat_id" to 42)).isError)
        assertTrue(call("get_history", mapOf("chat_id" to 43)).isError)
        verify(exactly = 1) { client.getHistory(42, any(), any(), any()) }
        verify(exactly = 0) { client.getHistory(43, any(), any(), any()) }
        val result = call("search_public_messages", mapOf("query" to "test", "chats" to listOf(42, 43)))
        assertFalse(result.isError)
        verify(atLeast = 1) { client.searchMessages(42, any(), any(), any()) }
        verify(exactly = 0) { client.searchMessages(43, any(), any(), any()) }
        assertNull(AccessPermissionContext.current())
        assertFails { context.currentAccount() }
        SecurityContextHolder.getContext().authentication = ApiKeyAuthToken("other", setOf("work"))
        assertTrue(call("get_history", mapOf("chat_id" to 42)).isError)
        verify(exactly = 1) { client.getHistory(42, any(), any(), any()) }
    }
}
