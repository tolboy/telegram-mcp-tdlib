package dev.telegrammcp.server.tool

import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema

/**
 * Contract for every MCP tool exposed by this server.
 *
 * Implementations are Spring components discovered automatically by
 * [dev.telegrammcp.server.config.McpConfig] and registered with the
 * MCP server at startup.
 *
 * ### Adding a new tool
 * 1. Create a `@Component` class implementing this interface.
 * 2. Return a [McpSchema.Tool] from [definition] (name, description, JSON Schema).
 * 3. Implement [execute] with your business logic.
 * 4. That's it — the tool is auto-registered on next restart.
 */
interface McpToolHandler {

    /**
     * MCP tool metadata: name, human-readable description, and input JSON Schema.
     */
    fun definition(): McpSchema.Tool

    /**
     * Executes the tool with the supplied [arguments].
     *
     * @param exchange the server exchange (carries session info, server capabilities)
     * @param arguments deserialized JSON arguments matching the input schema
     * @return [McpSchema.CallToolResult] wrapping text or structured content
     */
    fun execute(
        exchange: McpSyncServerExchange,
        arguments: Map<String, Any>,
    ): McpSchema.CallToolResult
}

/** Marker for tools that operate on server metadata rather than a Telegram account. */
interface AccountAgnosticMcpToolHandler : McpToolHandler
