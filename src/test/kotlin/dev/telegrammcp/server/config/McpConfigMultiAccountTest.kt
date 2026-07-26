package dev.telegrammcp.server.config

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.client.TelegramAccountContext
import dev.telegrammcp.server.client.TelegramAccountRegistry
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.exception.AccountAccessDeniedException
import dev.telegrammcp.server.model.AuditOutcome
import dev.telegrammcp.server.security.AccountAccessPolicy
import dev.telegrammcp.server.security.ApiKeyAuthToken
import dev.telegrammcp.server.service.AuditService
import dev.telegrammcp.server.service.ToolSurfacePolicy
import dev.telegrammcp.server.tool.McpToolHandler
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.mockk
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.security.core.context.SecurityContextHolder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class McpConfigMultiAccountTest {

    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `adds required account selector and removes it before the tool executes`() {
        val registry = TelegramAccountRegistry().also {
            it.register(TelegramAccountRegistry.AccountHandle("work", mockk<TelegramClientService>(relaxed = true)))
            it.register(TelegramAccountRegistry.AccountHandle("personal", mockk<TelegramClientService>(relaxed = true)))
        }
        val context = TelegramAccountContext(registry)
        val handler = RecordingHandler()

        val specification = McpConfig()
            .syncToolSpecifications(
                listOf(handler),
                registry,
                context,
                AccountAccessPolicy(registry),
                ServerModeProperties(),
                ToolSurfacePolicy(McpSecurityProperties(toolProfile = McpToolProfile.ALL)),
                AuditService(
                    props = ServerModeProperties(
                        audit = ServerModeProperties.AuditProps(enabled = false),
                    ),
                    meterRegistry = SimpleMeterRegistry(),
                    objectMapper = jacksonObjectMapper().findAndRegisterModules(),
                ),
            )
            .single()

        @Suppress("UNCHECKED_CAST")
        val properties = specification.tool().inputSchema()["properties"] as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val accountSchema = properties["account"] as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val required = specification.tool().inputSchema()["required"] as List<String>
        assertEquals(listOf("personal", "work"), accountSchema["enum"])
        assertTrue("account" in required)

        specification.callHandler().apply(
            mockk<McpSyncServerExchange>(),
            McpSchema.CallToolRequest(
                "test_tool",
                mapOf("account" to "work", "value" to "kept"),
                emptyMap(),
            ),
        )
        assertEquals(mapOf("value" to "kept"), handler.receivedArguments)
        assertFalse("account" in handler.receivedArguments)
    }

    @Test
    fun `audits account-scope denial before the tool executes`() {
        val registry = TelegramAccountRegistry().also {
            it.register(TelegramAccountRegistry.AccountHandle("work", mockk<TelegramClientService>(relaxed = true)))
            it.register(TelegramAccountRegistry.AccountHandle("personal", mockk<TelegramClientService>(relaxed = true)))
        }
        val handler = RecordingHandler()
        val audit = AuditService(
            props = ServerModeProperties(
                audit = ServerModeProperties.AuditProps(enabled = true),
            ),
            meterRegistry = SimpleMeterRegistry(),
            objectMapper = jacksonObjectMapper().findAndRegisterModules(),
        )
        SecurityContextHolder.getContext().authentication = ApiKeyAuthToken("work-agent", setOf("work"))
        val specification = McpConfig().syncToolSpecifications(
            handlers = listOf(handler),
            registry = registry,
            accountContext = TelegramAccountContext(registry),
            accountAccessPolicy = AccountAccessPolicy(registry),
            serverMode = ServerModeProperties(),
            toolSurfacePolicy = ToolSurfacePolicy(McpSecurityProperties(toolProfile = McpToolProfile.ALL)),
            auditService = audit,
        ).single()

        assertFailsWith<AccountAccessDeniedException> {
            specification.callHandler().apply(
                mockk<McpSyncServerExchange>(),
                McpSchema.CallToolRequest(
                    "test_tool",
                    mapOf("account" to "personal", "value" to "not-delivered"),
                    emptyMap(),
                ),
            )
        }

        assertEquals(0, handler.executionCount)
        val entry = audit.getRecentEntries().single()
        assertEquals(AuditOutcome.BLOCKED_GUARDRAIL, entry.outcome)
        assertEquals("personal", entry.account)
    }

    @Test
    fun `fallback audit retains the selected account context`() {
        val registry = TelegramAccountRegistry().also {
            it.register(TelegramAccountRegistry.AccountHandle("work", mockk<TelegramClientService>(relaxed = true)))
            it.register(TelegramAccountRegistry.AccountHandle("personal", mockk<TelegramClientService>(relaxed = true)))
        }
        val accountContext = TelegramAccountContext(registry)
        val audit = AuditService(
            props = ServerModeProperties(
                audit = ServerModeProperties.AuditProps(enabled = true),
            ),
            meterRegistry = SimpleMeterRegistry(),
            objectMapper = jacksonObjectMapper().findAndRegisterModules(),
            accountContext = accountContext,
        )
        val specification = McpConfig().syncToolSpecifications(
            handlers = listOf(RecordingHandler()),
            registry = registry,
            accountContext = accountContext,
            accountAccessPolicy = AccountAccessPolicy(registry),
            serverMode = ServerModeProperties(),
            toolSurfacePolicy = ToolSurfacePolicy(McpSecurityProperties(toolProfile = McpToolProfile.ALL)),
            auditService = audit,
        ).single()

        specification.callHandler().apply(
            mockk<McpSyncServerExchange>(),
            McpSchema.CallToolRequest(
                "test_tool",
                mapOf("account" to "work", "value" to "kept"),
                emptyMap(),
            ),
        )

        assertEquals("work", audit.getRecentEntries().single().account)
    }

    private class RecordingHandler : McpToolHandler {
        var receivedArguments: Map<String, Any> = emptyMap()
        var executionCount: Int = 0

        override fun definition(): McpSchema.Tool = McpSchema.Tool(
            "test_tool",
            null,
            "Test tool",
            mapOf("type" to "object", "properties" to mapOf("value" to mapOf("type" to "string")), "required" to emptyList<String>()),
            emptyMap(),
            null,
            emptyMap(),
            emptyList(),
        )

        override fun execute(
            exchange: McpSyncServerExchange,
            arguments: Map<String, Any>,
        ): McpSchema.CallToolResult {
            executionCount += 1
            receivedArguments = arguments
            return McpSchema.CallToolResult.builder().addTextContent("ok").isError(false).build()
        }
    }
}
