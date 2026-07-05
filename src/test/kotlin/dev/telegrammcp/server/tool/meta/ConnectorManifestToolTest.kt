package dev.telegrammcp.server.tool.meta

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.config.McpSecurityProperties
import dev.telegrammcp.server.config.McpToolProfile
import dev.telegrammcp.server.config.ServerModeProperties
import dev.telegrammcp.server.service.ToolSurfacePolicy
import dev.telegrammcp.server.tool.McpToolHandler
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConnectorManifestToolTest {

    private val objectMapper = jacksonObjectMapper().findAndRegisterModules()

    @Test
    fun `definition exposes manifest tool without arguments`() {
        val tool = ConnectorManifestTool(
            handlersProvider = handlersProvider(emptyList()),
            serverMode = ServerModeProperties(),
            toolSurfacePolicy = ToolSurfacePolicy(McpSecurityProperties()),
            objectMapper = objectMapper,
            meterRegistry = SimpleMeterRegistry(),
        )

        val definition = tool.definition()

        assertEquals("_manifest", definition.name())
        assertTrue(definition.description().contains("manifest"))
    }

    @Test
    fun `execute returns connector manifest with canonical self identifier and grouped tools`() {
        val sendMessage = fakeTool(
            name = "send_message",
            description = "Send a text message to a Telegram chat",
        )
        val listChats = fakeTool(
            name = "list_chats",
            description = "List Telegram chats",
        )
        val createTopic = fakeTool(
            name = "create_topic",
            description = "Create a forum topic",
        )
        val setForumTopicsEnabled = fakeTool(
            name = "set_forum_topics_enabled",
            description = "Enable forum topics",
        )
        val manifestTool = ConnectorManifestTool(
            handlersProvider = handlersProvider(listOf(sendMessage, listChats, createTopic, setForumTopicsEnabled)),
            serverMode = ServerModeProperties(),
            toolSurfacePolicy = ToolSurfacePolicy(McpSecurityProperties()),
            objectMapper = objectMapper,
            meterRegistry = SimpleMeterRegistry(),
        )

        val result = manifestTool.execute(mockk<McpSyncServerExchange>(relaxed = true), emptyMap())

        assertFalse(result.isError)
        val payload = objectMapper.readTree((result.content.first() as McpSchema.TextContent).text())
        assertEquals("telegram-mcp", payload["connector"].asText())
        assertEquals(5, payload["toolCount"].asInt())
        assertEquals(listOf("self"), payload["selfChatAliases"].map { it.asText() })
        assertTrue(payload["toolGroups"]["messages"].any { it.asText() == "send_message" })
        assertTrue(payload["toolGroups"]["chats"].any { it.asText() == "list_chats" })
        assertTrue(payload["toolGroups"]["chats"].any { it.asText() == "create_topic" })
        assertTrue(payload["toolGroups"]["chats"].any { it.asText() == "set_forum_topics_enabled" })
        assertTrue(payload["toolGroups"]["meta"].any { it.asText() == "_manifest" })
    }

    @Test
    fun `read-only manifest hides mutating tools`() {
        val manifestTool = ConnectorManifestTool(
            handlersProvider = handlersProvider(listOf(fakeTool("get_history", "Read history"), fakeTool("send_message", "Send"))),
            serverMode = ServerModeProperties(readOnly = true),
            toolSurfacePolicy = ToolSurfacePolicy(McpSecurityProperties()),
            objectMapper = objectMapper,
            meterRegistry = SimpleMeterRegistry(),
        )

        val result = manifestTool.execute(mockk<McpSyncServerExchange>(relaxed = true), emptyMap())
        val payload = objectMapper.readTree((result.content.first() as McpSchema.TextContent).text())

        assertEquals(listOf("_manifest", "get_history"), payload["tools"].map { it["name"].asText() })
    }

    @Test
    fun `manifest reports profile and filters absent tools`() {
        val manifestTool = ConnectorManifestTool(
            handlersProvider = handlersProvider(listOf(fakeTool("get_history", "Read"), fakeTool("send_message", "Send"), fakeTool("create_group", "Create"))),
            serverMode = ServerModeProperties(),
            toolSurfacePolicy = ToolSurfacePolicy(McpSecurityProperties(toolProfile = McpToolProfile.INBOX)),
            objectMapper = objectMapper,
            meterRegistry = SimpleMeterRegistry(),
        )

        val result = manifestTool.execute(mockk<McpSyncServerExchange>(relaxed = true), emptyMap())
        val payload = objectMapper.readTree((result.content.first() as McpSchema.TextContent).text())

        assertEquals("inbox", payload["toolProfile"].asText())
        assertEquals(listOf("_manifest", "get_history", "send_message"), payload["tools"].map { it["name"].asText() })
    }

    private fun handlersProvider(handlers: List<McpToolHandler>): ObjectProvider<McpToolHandler> =
        mockk {
            every { orderedStream() } returns handlers.stream()
        }

    private fun fakeTool(name: String, description: String): McpToolHandler =
        object : McpToolHandler {
            override fun definition(): McpSchema.Tool = McpSchema.Tool(
                name,
                null,
                description,
                mapOf("type" to "object", "properties" to emptyMap<String, Any>(), "required" to emptyList<String>()),
                emptyMap(),
                null,
                emptyMap(),
                emptyList(),
            )

            override fun execute(
                exchange: McpSyncServerExchange,
                arguments: Map<String, Any>,
            ): McpSchema.CallToolResult = error("not used")
        }
}
