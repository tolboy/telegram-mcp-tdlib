package dev.telegrammcp.server.model

/**
 * Public discovery document for launcher-style MCP integrations.
 */
data class McpServerDescriptor(
    val schemaVersion: Int = 1,
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val tags: List<String>,
    val transport: Transport,
    val authentication: Authentication,
    val capabilities: Capabilities,
    /** Curated tool surface currently advertised by this server instance. */
    val toolProfile: String = "all",
    val launcherHints: LauncherHints,
    val interactiveAuth: InteractiveAuth? = null,
) {
    data class Transport(
        val type: String,
        val basePath: String,
        val messagePath: String,
        val url: String,
        val messageUrl: String,
    )

    data class Authentication(
        val required: Boolean,
        val type: String,
        val acceptedHeaders: List<String>,
    )

    data class Capabilities(
        val toolCount: Int,
        val toolNames: List<String>,
    )

    data class LauncherHints(
        val connectorType: String,
        val deploymentMode: String,
        val defaultBindHost: String,
        val multiRepoReady: Boolean,
    )

    data class InteractiveAuth(
        val supported: Boolean,
        val stateEndpoint: String,
        val credentialsEndpoint: String,
        val submitCodeEndpoint: String,
        val submitPasswordEndpoint: String,
        val requestQrEndpoint: String,
        val logoutEndpoint: String,
    )
}
