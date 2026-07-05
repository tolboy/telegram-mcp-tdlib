package dev.telegrammcp.server.service

import dev.telegrammcp.server.config.McpDescriptorProperties
import dev.telegrammcp.server.config.McpSecurityProperties
import dev.telegrammcp.server.config.McpToolProfile
import dev.telegrammcp.server.config.ServerModeProperties
import dev.telegrammcp.server.client.TelegramAccountRegistry
import dev.telegrammcp.server.tool.McpToolHandler
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpServerDescriptorServiceTest {

    @Test
    fun `build exposes sorted tools and accepted auth headers`() {
        val service = McpServerDescriptorService(
            descriptorProperties = McpDescriptorProperties(
                id = "telegram-mcp-server",
                displayName = "Telegram MCP Server",
                description = "Telegram connector",
                defaultBindHost = "127.0.0.1",
            ),
            securityProperties = McpSecurityProperties(
                security = McpSecurityProperties.SecurityProps(
                    apiKey = "secret-key",
                    headerName = "X-Internal-Api-Key",
                ),
            ),
            accountRegistry = TelegramAccountRegistry(),
            handlers = listOf(fakeHandler("z_tool"), fakeHandler("a_tool")),
            serverMode = ServerModeProperties(),
            toolSurfacePolicy = ToolSurfacePolicy(McpSecurityProperties()),
            environment = MockEnvironment()
                .withProperty("spring.ai.mcp.server.version", "0.1.0")
                .withProperty("spring.ai.mcp.server.streamable-http.mcp-endpoint", "/mcp"),
        )

        val descriptor = service.build("http://127.0.0.1:8080")

        assertEquals(listOf("a_tool", "z_tool"), descriptor.capabilities.toolNames)
        assertEquals(2, descriptor.capabilities.toolCount)
        assertEquals("http://127.0.0.1:8080/mcp", descriptor.transport.url)
        assertEquals("streamable-http", descriptor.transport.type)
        assertEquals("http://127.0.0.1:8080/mcp", descriptor.transport.messageUrl)
        assertEquals(listOf("X-Internal-Api-Key", "Authorization", "X-MCP-API-Key"), descriptor.authentication.acceptedHeaders)
        assertTrue(descriptor.authentication.required)
        assertTrue(descriptor.launcherHints.multiRepoReady)
    }

    @Test
    fun `build reports auth disabled when no api key is configured`() {
        val service = McpServerDescriptorService(
            descriptorProperties = McpDescriptorProperties(),
            securityProperties = McpSecurityProperties(),
            accountRegistry = TelegramAccountRegistry(),
            handlers = listOf(fakeHandler("only_tool")),
            serverMode = ServerModeProperties(),
            toolSurfacePolicy = ToolSurfacePolicy(McpSecurityProperties()),
            environment = MockEnvironment(),
        )

        val descriptor = service.build("http://localhost:8080")

        assertEquals("none", descriptor.authentication.type)
        assertFalse(descriptor.authentication.required)
    }

    @Test
    fun `build hides write tools when read-only mode is enabled`() {
        val service = McpServerDescriptorService(
            descriptorProperties = McpDescriptorProperties(),
            securityProperties = McpSecurityProperties(),
            accountRegistry = TelegramAccountRegistry(),
            handlers = listOf(fakeHandler("get_history"), fakeHandler("send_message")),
            serverMode = ServerModeProperties(readOnly = true),
            toolSurfacePolicy = ToolSurfacePolicy(McpSecurityProperties()),
            environment = MockEnvironment(),
        )

        assertEquals(listOf("get_history"), service.build("http://localhost:8080").capabilities.toolNames)
    }

    @Test
    fun `build reports the active tool profile`() {
        val service = McpServerDescriptorService(
            descriptorProperties = McpDescriptorProperties(),
            securityProperties = McpSecurityProperties(),
            accountRegistry = TelegramAccountRegistry(),
            handlers = listOf(fakeHandler("get_history"), fakeHandler("send_message")),
            serverMode = ServerModeProperties(),
            toolSurfacePolicy = ToolSurfacePolicy(McpSecurityProperties(toolProfile = McpToolProfile.READER)),
            environment = MockEnvironment(),
        )

        val descriptor = service.build("http://localhost:8080")

        assertEquals("reader", descriptor.toolProfile)
        assertEquals(listOf("get_history"), descriptor.capabilities.toolNames)
    }

    private fun fakeHandler(name: String): McpToolHandler = object : McpToolHandler {
        override fun definition(): McpSchema.Tool = McpSchema.Tool(
            name,
            null,
            "test",
            mapOf("type" to "object", "properties" to emptyMap<String, Any>(), "required" to emptyList<String>()),
            emptyMap(),
            null,
            emptyMap(),
            emptyList(),
        )

        override fun execute(
            exchange: McpSyncServerExchange,
            arguments: Map<String, Any>,
        ): McpSchema.CallToolResult = McpSchema.CallToolResult.builder().isError(false).build()
    }
}
