package dev.telegrammcp.server.config

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.client.TelegramAccountContext
import dev.telegrammcp.server.client.TelegramAccountRegistry
import dev.telegrammcp.server.client.TelegramAuthGate
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.exception.AccountAccessDeniedException
import dev.telegrammcp.server.exception.ApprovalDeniedException
import dev.telegrammcp.server.model.AuditOutcome
import dev.telegrammcp.server.model.InlineButtonInfo
import dev.telegrammcp.server.security.AccountAccessPolicy
import dev.telegrammcp.server.security.ApiKeyAuthToken
import dev.telegrammcp.server.service.AuditService
import dev.telegrammcp.server.service.OperationGuardService
import dev.telegrammcp.server.service.DestructiveApprovalService
import dev.telegrammcp.server.service.EntityResolverService
import dev.telegrammcp.server.service.GuardrailService
import dev.telegrammcp.server.service.ToolSurfacePolicy
import dev.telegrammcp.server.tool.McpToolHandler
import dev.telegrammcp.server.tool.message.InlineButtonTokens
import dev.telegrammcp.server.tool.message.PressInlineButtonTool
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.security.core.context.SecurityContextHolder
import java.time.Duration
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
                approvalService(),
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
            mockk<McpSyncServerExchange> {
                io.mockk.every { transportContext() } returns io.modelcontextprotocol.common.McpTransportContext.EMPTY
            },
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
            approvalService = approvalService(),
        ).single()

        assertToolError("ACCOUNT_FORBIDDEN") {
            specification.callHandler().apply(
                mockk<McpSyncServerExchange> {
                io.mockk.every { transportContext() } returns io.modelcontextprotocol.common.McpTransportContext.EMPTY
            },
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
            approvalService = approvalService(),
        ).single()

        specification.callHandler().apply(
            mockk<McpSyncServerExchange> {
                io.mockk.every { transportContext() } returns io.modelcontextprotocol.common.McpTransportContext.EMPTY
            },
            McpSchema.CallToolRequest(
                "test_tool",
                mapOf("account" to "work", "value" to "kept"),
                emptyMap(),
            ),
        )

        assertEquals("work", audit.getRecentEntries().single().account)
    }

    @Test
    fun `local tools wait for the selected expected account and refuse a failed session`() {
        val gate = TelegramAuthGate("work", Duration.ofMillis(10))
        val registry = TelegramAccountRegistry().also {
            it.register(TelegramAccountRegistry.AccountHandle("work", mockk(), expectedAccountGate = { gate }))
            it.register(TelegramAccountRegistry.AccountHandle("personal", mockk()))
        }
        val handler = RecordingHandler("changes_since")
        val specification = McpConfig().syncToolSpecifications(
            handlers = listOf(handler),
            registry = registry,
            accountContext = TelegramAccountContext(registry),
            accountAccessPolicy = AccountAccessPolicy(registry),
            serverMode = ServerModeProperties(),
            toolSurfacePolicy = ToolSurfacePolicy(McpSecurityProperties(toolProfile = McpToolProfile.ALL)),
            auditService = AuditService(
                ServerModeProperties(audit = ServerModeProperties.AuditProps(enabled = false)),
                SimpleMeterRegistry(),
                jacksonObjectMapper().findAndRegisterModules(),
            ),
            approvalService = approvalService(),
        ).single()
        val exchange = mockk<McpSyncServerExchange> {
            io.mockk.every { transportContext() } returns io.modelcontextprotocol.common.McpTransportContext.EMPTY
        }
        fun call(account: String) = specification.callHandler().apply(
            exchange,
            McpSchema.CallToolRequest("changes_since", mapOf("account" to account), emptyMap()),
        )

        assertToolError("AUTH_REQUIRED") { call("work") }
        assertEquals(0, handler.executionCount)
        assertFalse(call("personal").isError)
        gate.markReady()
        assertFalse(call("work").isError)
        gate.markFailed("TDLib session closed")
        assertToolError("AUTH_REQUIRED") { call("work") }
        assertEquals(2, handler.executionCount)
    }

    @Test
    fun `inline button dispatch asks about the token-bound keyboard snapshot before pressing`() {
        val client = mockk<TelegramClientService>()
        val registry = TelegramAccountRegistry().also {
            it.register(TelegramAccountRegistry.AccountHandle("work", client))
        }
        val context = TelegramAccountContext(registry)
        val props = ServerModeProperties(readOnly = false, audit = ServerModeProperties.AuditProps(enabled = false))
        val metrics = SimpleMeterRegistry()
        val mapper = jacksonObjectMapper().findAndRegisterModules()
        val audit = AuditService(props, metrics, mapper, context)
        val signer = InlineButtonTokens(context)
        val button = InlineButtonInfo(row = 0, index = 0, text = "Continue", type = "callback", callbackData = "continue")
        val token = context.withAccount("work") { signer.tokenFor(42, 100, button) }
        every { client.listInlineButtons(42, 100) } returns listOf(button)
        every { client.pressInlineButton(42, 100, 0, null, button) } returns "Done"
        val resolver = mockk<EntityResolverService> {
            every { resolve("@target" as Any) } returns 42L
        }
        val handler = PressInlineButtonTool(
            client, resolver, mockk<GuardrailService>(relaxed = true), mockk<OperationGuardService>(relaxed = true),
            audit, mapper, metrics, signer,
        )
        val approval = mockk<DestructiveApprovalService>(relaxed = true)
        var permitted = false
        every { approval.requireApproval(any(), "press_inline_button", any(), any()) } answers {
            assertEquals(
                DestructiveApprovalService.InlineButtonTarget(42, 100, 0, "Continue", "callback"),
                arg<DestructiveApprovalService.InlineButtonTarget>(3),
            )
            verify(exactly = 0) { client.pressInlineButton(any(), any(), any(), any(), any()) }
            if (!permitted) throw ApprovalDeniedException("press_inline_button", "declined")
        }
        val specification = McpConfig().syncToolSpecifications(
            listOf(handler), registry, context, AccountAccessPolicy(registry), props,
            ToolSurfacePolicy(McpSecurityProperties(toolProfile = McpToolProfile.ALL)), audit, approval,
        ).single()
        val exchange = mockk<McpSyncServerExchange> {
            every { transportContext() } returns io.modelcontextprotocol.common.McpTransportContext.EMPTY
        }
        fun call() = specification.callHandler().apply(
            exchange,
            McpSchema.CallToolRequest(
                "press_inline_button",
                mapOf("account" to "work", "chat_id" to "@target", "message_id" to 100, "button_token" to token),
                emptyMap(),
            ),
        )

        assertToolError("APPROVAL_DENIED") { call() }
        permitted = true
        assertFalse(call().isError)
        verify(exactly = 1) { client.pressInlineButton(42, 100, 0, null, button) }
    }

    /** Approval defaults to off, so these cases exercise account routing unchanged. */
    private fun approvalService(): DestructiveApprovalService {
        val props = ServerModeProperties()
        return DestructiveApprovalService(props, OperationGuardService(props, mockk(relaxed = true)))
    }

    private class RecordingHandler(private val name: String = "test_tool") : McpToolHandler {
        var receivedArguments: Map<String, Any> = emptyMap()
        var executionCount: Int = 0

        override fun definition(): McpSchema.Tool = McpSchema.Tool(
            name,
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
