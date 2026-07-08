package dev.telegrammcp.server.config

import dev.telegrammcp.server.client.TelegramAccountContext
import dev.telegrammcp.server.client.TelegramAccountRegistry
import dev.telegrammcp.server.security.AccountAccessPolicy
import dev.telegrammcp.server.service.ToolSurfacePolicy
import dev.telegrammcp.server.tool.AccountAgnosticMcpToolHandler
import io.mockk.every
import io.mockk.mockk
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class McpConfigReadOnlyTest {

    @Test
    fun `read-only mode does not register write or quota-consuming tools`() {
        val registry = TelegramAccountRegistry()
        val specifications = McpConfig().syncToolSpecifications(
            handlers = listOf(
                TestHandler("get_history"),
                TestHandler("send_message"),
                TestHandler("transcribe_voice_note"),
                TestHandler("download_media"),
                TestHandler("register_internal_chat"),
                TestHandler("configure_chat_folder"),
                TestHandler("delete_chat_folder"),
                TestHandler("schedule_message"),
                TestHandler("reschedule_message"),
                TestHandler("cancel_scheduled_message"),
            ),
            registry = registry,
            accountContext = TelegramAccountContext(registry),
            accountAccessPolicy = AccountAccessPolicy(registry),
            serverMode = ServerModeProperties(readOnly = true),
            toolSurfacePolicy = ToolSurfacePolicy(McpSecurityProperties()),
        )

        assertEquals(listOf("get_history"), specifications.map { it.tool().name() })
    }

    @Test
    fun `write tools remain registered when read-only mode is disabled`() {
        val registry = TelegramAccountRegistry()
        val specifications = McpConfig().syncToolSpecifications(
            handlers = listOf(TestHandler("get_history"), TestHandler("send_message")),
            registry = registry,
            accountContext = TelegramAccountContext(registry),
            accountAccessPolicy = AccountAccessPolicy(registry),
            serverMode = ServerModeProperties(readOnly = false),
            toolSurfacePolicy = ToolSurfacePolicy(McpSecurityProperties()),
        )

        assertEquals(listOf("get_history", "send_message"), specifications.map { it.tool().name() })
    }

    @Test
    fun `inbox profile hides community administration tools`() {
        val registry = TelegramAccountRegistry()
        val specifications = McpConfig().syncToolSpecifications(
            handlers = listOf(TestHandler("get_history"), TestHandler("send_message"), TestHandler("create_group")),
            registry = registry,
            accountContext = TelegramAccountContext(registry),
            accountAccessPolicy = AccountAccessPolicy(registry),
            serverMode = ServerModeProperties(readOnly = false),
            toolSurfacePolicy = ToolSurfacePolicy(McpSecurityProperties(toolProfile = McpToolProfile.INBOX)),
        )

        assertEquals(listOf("get_history", "send_message"), specifications.map { it.tool().name() })
    }

    @Test
    fun `registered tools publish conservative MCP behavior annotations`() {
        val registry = TelegramAccountRegistry()
        val specifications = McpConfig().syncToolSpecifications(
            handlers = listOf(
                TestHandler("get_history"),
                TestHandler("send_message"),
                TestHandler("download_media"),
                TestHandler("delete_message"),
            ),
            registry = registry,
            accountContext = TelegramAccountContext(registry),
            accountAccessPolicy = AccountAccessPolicy(registry),
            serverMode = ServerModeProperties(readOnly = false),
            toolSurfacePolicy = ToolSurfacePolicy(McpSecurityProperties()),
        ).associateBy { it.tool().name() }

        val readAnnotations = specifications.getValue("get_history").tool().annotations()
        assertTrue(readAnnotations.readOnlyHint())
        assertFalse(readAnnotations.destructiveHint())
        assertTrue(readAnnotations.openWorldHint())

        val writeAnnotations = specifications.getValue("send_message").tool().annotations()
        assertFalse(writeAnnotations.readOnlyHint())
        assertFalse(writeAnnotations.destructiveHint())
        assertFalse(writeAnnotations.idempotentHint())

        assertFalse(specifications.getValue("download_media").tool().annotations().readOnlyHint())

        val destructiveAnnotations = specifications.getValue("delete_message").tool().annotations()
        assertFalse(destructiveAnnotations.readOnlyHint())
        assertTrue(destructiveAnnotations.destructiveHint())
    }

    @Test
    fun `execution fails closed when a write tool is registered despite read-only mode`() {
        // Simulate a surface-policy regression that leaves a write tool
        // visible: the dispatch wrapper must still refuse to execute it.
        val registry = TelegramAccountRegistry()
        val brokenSurfacePolicy = mockk<ToolSurfacePolicy>(relaxed = true)
        every { brokenSurfacePolicy.isVisible(any(), any()) } returns true
        every { brokenSurfacePolicy.profile } returns McpToolProfile.ALL

        val specification = McpConfig().syncToolSpecifications(
            handlers = listOf(TestHandler("send_message")),
            registry = registry,
            accountContext = TelegramAccountContext(registry),
            accountAccessPolicy = AccountAccessPolicy(registry),
            serverMode = ServerModeProperties(readOnly = true),
            toolSurfacePolicy = brokenSurfacePolicy,
        ).single()

        val result = specification.callHandler().apply(
            mockk<McpSyncServerExchange>(),
            McpSchema.CallToolRequest("send_message", mapOf("chat_id" to 1, "text" to "hi"), emptyMap()),
        )

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("read-only"), "Expected a read-only rejection, got: $text")
    }

    @Test
    fun `duplicate tool names fail startup deterministically`() {
        val registry = TelegramAccountRegistry()

        assertFailsWith<IllegalArgumentException> {
            McpConfig().syncToolSpecifications(
                handlers = listOf(TestHandler("get_history"), TestHandler("get_history")),
                registry = registry,
                accountContext = TelegramAccountContext(registry),
                accountAccessPolicy = AccountAccessPolicy(registry),
                serverMode = ServerModeProperties(),
                toolSurfacePolicy = ToolSurfacePolicy(McpSecurityProperties()),
            )
        }
    }

    private class TestHandler(private val name: String) : AccountAgnosticMcpToolHandler {
        override fun definition(): McpSchema.Tool = McpSchema.Tool(
            name,
            null,
            "Test tool",
            mapOf("type" to "object", "properties" to emptyMap<String, Any>()),
            emptyMap(),
            null,
            emptyMap(),
            emptyList(),
        )

        override fun execute(
            exchange: McpSyncServerExchange,
            arguments: Map<String, Any>,
        ): McpSchema.CallToolResult = McpSchema.CallToolResult.builder().addTextContent("ok").isError(false).build()
    }
}
