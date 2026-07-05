package dev.telegrammcp.server.config

import dev.telegrammcp.server.client.TelegramAccountContext
import dev.telegrammcp.server.client.TelegramAccountRegistry
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.security.AccountAccessPolicy
import dev.telegrammcp.server.service.ToolSurfacePolicy
import dev.telegrammcp.server.tool.McpToolHandler
import io.mockk.mockk
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpConfigMultiAccountTest {

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
                ToolSurfacePolicy(McpSecurityProperties()),
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

    private class RecordingHandler : McpToolHandler {
        var receivedArguments: Map<String, Any> = emptyMap()

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
            receivedArguments = arguments
            return McpSchema.CallToolResult.builder().addTextContent("ok").isError(false).build()
        }
    }
}
