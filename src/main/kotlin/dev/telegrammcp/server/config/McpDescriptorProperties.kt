package dev.telegrammcp.server.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Stable discovery metadata for MCP host and orchestrator integrations.
 *
 * Each connector repository can expose the same shape under a well-known path,
 * which lets a future local "box of MCP servers" discover and present them
 * consistently without embedding repository-specific logic.
 */
@ConfigurationProperties(prefix = "mcp.descriptor")
data class McpDescriptorProperties(
    val id: String = "telegram-mcp-server",
    val displayName: String = "Telegram MCP Server",
    val description: String = "TDLib-backed local-first MCP connector for Telegram workflows",
    val connectorType: String = "telegram",
    val deploymentMode: String = "local-first",
    val defaultBindHost: String = "127.0.0.1",
    val transportBasePath: String = "/mcp",
    val tags: List<String> = listOf("telegram", "tdlib", "mcp", "local-first"),
)
