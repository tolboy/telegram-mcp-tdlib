package dev.telegrammcp.server.config

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.client.TelegramAccountContext
import dev.telegrammcp.server.client.TelegramAccountRegistry
import dev.telegrammcp.server.exception.ApprovalDeniedException
import dev.telegrammcp.server.model.AuditOutcome
import dev.telegrammcp.server.security.AccountAccessPolicy
import dev.telegrammcp.server.service.AuditService
import dev.telegrammcp.server.service.DestructiveApprovalService
import dev.telegrammcp.server.service.OperationGuardService
import dev.telegrammcp.server.service.ToolSurfacePolicy
import dev.telegrammcp.server.tool.McpToolHandler
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Approval is only worth having if it runs before the operation does.
 *
 * The service decides the answer; this pins where that decision sits. The gate
 * lives at the shared dispatch, ahead of the handler, so nothing reaches
 * Telegram while a human is still being asked — and so a destructive tool added
 * later inherits the gate instead of having to remember it.
 */
class McpConfigApprovalTest {

    @Test
    fun `a declined approval blocks the handler and records the outcome`() {
        val handler = CountingHandler("ban_user")
        val audit = auditService()
        val specification = specificationFor(handler, audit, ServerModeProperties.ApprovalMode.ELICITATION)

        // A denial surfaces the same way as the other dispatch-level guards:
        // as a failed call, not a result the model could mistake for success.
        assertFailsWith<ApprovalDeniedException> {
            specification.callHandler().apply(
                decliningExchange(),
                McpSchema.CallToolRequest("ban_user", mapOf("chat_id" to 1, "user_id" to 2), emptyMap()),
            )
        }

        assertEquals(0, handler.executionCount, "the handler must not run before approval")
        assertEquals(AuditOutcome.BLOCKED_APPROVAL, audit.getRecentEntries().single().outcome)
    }

    @Test
    fun `an approved operation reaches the handler`() {
        val handler = CountingHandler("ban_user")
        val audit = auditService()
        val specification = specificationFor(handler, audit, ServerModeProperties.ApprovalMode.ELICITATION)

        specification.callHandler().apply(
            acceptingExchange(),
            McpSchema.CallToolRequest("ban_user", mapOf("chat_id" to 1, "user_id" to 2), emptyMap()),
        )

        assertEquals(1, handler.executionCount)
        assertEquals(AuditOutcome.SUCCESS, audit.getRecentEntries().single().outcome)
    }

    /**
     * `confirmed: true` is exactly what a prompt-injected model would send, so
     * it must not stand in for the human when approval is required.
     */
    @Test
    fun `a caller-asserted confirmation does not substitute for approval`() {
        val handler = CountingHandler("delete_message")
        val specification = specificationFor(handler, auditService(), ServerModeProperties.ApprovalMode.ELICITATION)

        assertFailsWith<ApprovalDeniedException> {
            specification.callHandler().apply(
                decliningExchange(),
                McpSchema.CallToolRequest("delete_message", mapOf("confirmed" to true), emptyMap()),
            )
        }

        assertEquals(0, handler.executionCount)
    }

    @Test
    fun `the default mode leaves dispatch untouched`() {
        val handler = CountingHandler("ban_user")
        val specification = specificationFor(handler, auditService(), ServerModeProperties.ApprovalMode.OFF)
        val exchange = mockk<McpSyncServerExchange>(relaxed = true)

        specification.callHandler().apply(
            exchange,
            McpSchema.CallToolRequest("ban_user", mapOf("chat_id" to 1), emptyMap()),
        )

        assertEquals(1, handler.executionCount)
        io.mockk.verify(exactly = 0) { exchange.createElicitation(any()) }
    }

    private fun specificationFor(
        handler: McpToolHandler,
        audit: AuditService,
        approval: ServerModeProperties.ApprovalMode,
    ): McpServerFeaturesSyncToolSpecification {
        // Account routing resolves before the approval gate so the audit entry
        // is attributed correctly; one account keeps that selection unambiguous.
        val registry = TelegramAccountRegistry().also {
            it.register(
                TelegramAccountRegistry.AccountHandle(
                    "default",
                    mockk<dev.telegrammcp.server.client.TelegramClientService>(relaxed = true),
                ),
            )
        }
        val props = ServerModeProperties(
            readOnly = false,
            confirmation = ServerModeProperties.ConfirmationProps(enabled = false, approval = approval),
        )
        return McpConfig().syncToolSpecifications(
            handlers = listOf(handler),
            registry = registry,
            accountContext = TelegramAccountContext(registry),
            accountAccessPolicy = AccountAccessPolicy(registry),
            serverMode = props,
            toolSurfacePolicy = ToolSurfacePolicy(McpSecurityProperties(toolProfile = McpToolProfile.ALL)),
            auditService = audit,
            approvalService = DestructiveApprovalService(
                props,
                OperationGuardService(props, mockk(relaxed = true)),
            ),
        ).single()
    }

    private fun acceptingExchange(): McpSyncServerExchange =
        elicitingExchange(McpSchema.ElicitResult.Action.ACCEPT)

    private fun decliningExchange(): McpSyncServerExchange =
        elicitingExchange(McpSchema.ElicitResult.Action.DECLINE)

    private fun elicitingExchange(action: McpSchema.ElicitResult.Action): McpSyncServerExchange {
        val exchange = mockk<McpSyncServerExchange>(relaxed = true)
        every { exchange.clientCapabilities } returns
            McpSchema.ClientCapabilities.builder().elicitation().build()
        every { exchange.createElicitation(any()) } returns McpSchema.ElicitResult(action, emptyMap())
        return exchange
    }

    private fun auditService(): AuditService = AuditService(
        props = ServerModeProperties(audit = ServerModeProperties.AuditProps(enabled = true)),
        meterRegistry = SimpleMeterRegistry(),
        objectMapper = jacksonObjectMapper().findAndRegisterModules(),
    )

    private class CountingHandler(private val name: String) : McpToolHandler {
        var executionCount: Int = 0

        override fun definition(): McpSchema.Tool = McpSchema.Tool.builder()
            .name(name)
            .description("test handler")
            .inputSchema(McpSchema.JsonSchema("object", emptyMap(), emptyList(), false, emptyMap(), emptyMap()))
            .build()

        override fun execute(
            exchange: McpSyncServerExchange,
            arguments: Map<String, Any>,
        ): McpSchema.CallToolResult {
            executionCount += 1
            return McpSchema.CallToolResult.builder().addTextContent("ok").isError(false).build()
        }
    }
}

private typealias McpServerFeaturesSyncToolSpecification =
    io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification
