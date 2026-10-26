package dev.telegrammcp.server.config

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.client.TelegramAccountContext
import dev.telegrammcp.server.client.TelegramAccountRegistry
import dev.telegrammcp.server.client.ToolCallDeadline
import dev.telegrammcp.server.security.AccountAccessPolicy
import dev.telegrammcp.server.service.AuditService
import dev.telegrammcp.server.service.DestructiveApprovalService
import dev.telegrammcp.server.service.OperationGuardService
import dev.telegrammcp.server.service.ToolSurfacePolicy
import dev.telegrammcp.server.tool.AccountAgnosticMcpToolHandler
import dev.telegrammcp.server.tool.McpToolHandler
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.mockk
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The tool-call deadline is opened by the MCP dispatch, not by each handler:
 * about half of the handlers never go through ToolSupport.execute, and they
 * must still answer before the MCP client gives up on the call.
 */
@SpringBootTest
@ActiveProfiles("test")
class McpConfigDeadlineTest {

    @Autowired
    private lateinit var handlers: List<McpToolHandler>

    @Test
    fun `every registered handler runs with the tool-call deadline open`() {
        val probes = handlers.map { probeFor(it) }
        val specifications = specificationsFor(probes.map { it.handler })
        assertEquals(handlers.size, specifications.size, "every handler must be registered")

        val exchange = mockk<McpSyncServerExchange>(relaxed = true)
        specifications.forEach { specification ->
            specification.callHandler().apply(
                exchange,
                McpSchema.CallToolRequest(specification.tool().name(), emptyMap(), emptyMap()),
            )
        }

        probes.forEach { probe ->
            assertEquals(1, probe.calls.size, "${probe.name} must have been invoked once")
            assertTrue(probe.calls.single().active, "${probe.name} ran without a tool-call deadline")
            assertEquals(
                ToolCallDeadline.budget.toNanos(), probe.calls.single().budgetNanos,
                "${probe.name} must run under exactly one deadline with the configured budget",
            )
        }
        assertFalse(ToolCallDeadline.isActive, "the deadline must close when the call returns")
    }

    @Test
    fun `the deadline is opened once and closes after a failing handler`() {
        val failing = object : McpToolHandler {
            var remaining: Long? = null
            override fun definition(): McpSchema.Tool = tool("failing_tool")
            override fun execute(exchange: McpSyncServerExchange, arguments: Map<String, Any>): McpSchema.CallToolResult {
                remaining = ToolCallDeadline.remainingNanos()
                throw IllegalStateException("boom")
            }
        }
        val result = specificationsFor(listOf(failing)).single().callHandler().apply(
            mockk(relaxed = true), McpSchema.CallToolRequest("failing_tool", emptyMap(), emptyMap()),
        )
        assertTrue(result.isError == true)
        assertTrue(requireNotNull(failing.remaining) > 0)
        assertFalse(ToolCallDeadline.isActive)
    }

    private class Call(val active: Boolean, val budgetNanos: Long?)

    private class Probe(val name: String, val handler: McpToolHandler, val calls: MutableList<Call>)

    /** Keeps the real definition (and account-agnostic marker) but records the deadline instead of executing. */
    private fun probeFor(real: McpToolHandler): Probe {
        val calls = mutableListOf<Call>()
        val record = {
            calls += Call(ToolCallDeadline.isActive, ToolCallDeadline.budgetNanos())
            McpSchema.CallToolResult.builder().addTextContent("{}").build()
        }
        val handler = if (real is AccountAgnosticMcpToolHandler) {
            object : AccountAgnosticMcpToolHandler {
                override fun definition() = real.definition()
                override fun execute(exchange: McpSyncServerExchange, arguments: Map<String, Any>) = record()
            }
        } else {
            object : McpToolHandler {
                override fun definition() = real.definition()
                override fun execute(exchange: McpSyncServerExchange, arguments: Map<String, Any>) = record()
            }
        }
        return Probe(real.definition().name(), handler, calls)
    }

    private fun specificationsFor(handlers: List<McpToolHandler>) = run {
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
            confirmation = ServerModeProperties.ConfirmationProps(
                enabled = false, approval = ServerModeProperties.ApprovalMode.OFF,
            ),
        )
        McpConfig().syncToolSpecifications(
            handlers = handlers,
            registry = registry,
            accountContext = TelegramAccountContext(registry),
            accountAccessPolicy = AccountAccessPolicy(registry),
            serverMode = props,
            toolSurfacePolicy = ToolSurfacePolicy(McpSecurityProperties(toolProfile = McpToolProfile.ALL)),
            auditService = AuditService(
                props = props,
                meterRegistry = SimpleMeterRegistry(),
                objectMapper = jacksonObjectMapper().findAndRegisterModules(),
            ),
            approvalService = DestructiveApprovalService(props, OperationGuardService(props, mockk(relaxed = true))),
        )
    }

    private fun tool(name: String): McpSchema.Tool = McpSchema.Tool.builder()
        .name(name)
        .description("test handler")
        .inputSchema(McpSchema.JsonSchema("object", emptyMap(), emptyList(), false, emptyMap(), emptyMap()))
        .build()
}
