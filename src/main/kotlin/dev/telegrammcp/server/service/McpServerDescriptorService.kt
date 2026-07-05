package dev.telegrammcp.server.service

import dev.telegrammcp.server.config.McpDescriptorProperties
import dev.telegrammcp.server.config.McpAuthMode
import dev.telegrammcp.server.config.McpSecurityProperties
import dev.telegrammcp.server.config.ServerModeProperties
import dev.telegrammcp.server.client.TelegramAccountRegistry
import dev.telegrammcp.server.model.McpServerDescriptor
import dev.telegrammcp.server.tool.McpToolHandler
import org.springframework.core.env.Environment
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Service

@Service
class McpServerDescriptorService(
    private val descriptorProperties: McpDescriptorProperties,
    private val securityProperties: McpSecurityProperties,
    private val accountRegistry: TelegramAccountRegistry,
    handlers: List<McpToolHandler>,
    serverMode: ServerModeProperties,
    private val toolSurfacePolicy: ToolSurfacePolicy,
    private val environment: Environment,
) {

    private val toolNames = handlers
        .asSequence()
        .map { it.definition().name() }
        .filter { toolSurfacePolicy.isVisible(it, serverMode.readOnly) }
        .sorted()
        .toList()

    fun build(baseUrl: String): McpServerDescriptor {
        val normalizedBaseUrl = baseUrl.trimEnd('/')
        val endpoint = environment.getProperty(
            "spring.ai.mcp.server.streamable-http.mcp-endpoint",
            descriptorProperties.transportBasePath,
        )

        return McpServerDescriptor(
            id = descriptorProperties.id,
            name = descriptorProperties.displayName,
            version = environment.getProperty("spring.ai.mcp.server.version", "0.1.0"),
            description = descriptorProperties.description,
            tags = descriptorProperties.tags,
            transport = McpServerDescriptor.Transport(
                type = "streamable-http",
                basePath = endpoint,
                // Kept for schemaVersion 1 consumers; Streamable HTTP uses
                // one endpoint for both requests and responses.
                messagePath = endpoint,
                url = "$normalizedBaseUrl$endpoint",
                messageUrl = "$normalizedBaseUrl$endpoint",
            ),
            authentication = McpServerDescriptor.Authentication(
                required = securityProperties.security.mode == McpAuthMode.OAUTH ||
                    securityProperties.authenticationConfigured,
                type = when {
                    securityProperties.security.mode == McpAuthMode.OAUTH -> "oauth2"
                    securityProperties.authenticationConfigured -> "apiKey"
                    else -> "none"
                },
                acceptedHeaders = acceptedHeaders(),
            ),
            capabilities = McpServerDescriptor.Capabilities(
                toolCount = toolNames.size,
                toolNames = toolNames,
            ),
            toolProfile = toolSurfacePolicy.profile.name.lowercase().replace('_', '-'),
            launcherHints = McpServerDescriptor.LauncherHints(
                connectorType = descriptorProperties.connectorType,
                deploymentMode = descriptorProperties.deploymentMode,
                defaultBindHost = descriptorProperties.defaultBindHost,
                multiRepoReady = true,
            ),
            interactiveAuth = McpServerDescriptor.InteractiveAuth(
                supported = accountRegistry.labels() == listOf("default"),
                stateEndpoint = "$normalizedBaseUrl/auth/state",
                credentialsEndpoint = "$normalizedBaseUrl/auth/credentials",
                submitCodeEndpoint = "$normalizedBaseUrl/auth/submit-code",
                submitPasswordEndpoint = "$normalizedBaseUrl/auth/submit-password",
                requestQrEndpoint = "$normalizedBaseUrl/auth/request-qr",
                logoutEndpoint = "$normalizedBaseUrl/auth/logout",
            ),
        )
    }

    private fun acceptedHeaders(): List<String> {
        if (securityProperties.security.mode == McpAuthMode.OAUTH) return listOf(HttpHeaders.AUTHORIZATION)
        val configuredHeader = securityProperties.security.headerName.trim().ifBlank { HttpHeaders.AUTHORIZATION }
        return buildList {
            add(configuredHeader)
            if (!configuredHeader.equals(HttpHeaders.AUTHORIZATION, ignoreCase = true)) {
                add(HttpHeaders.AUTHORIZATION)
            }
            if (!configuredHeader.equals("X-MCP-API-Key", ignoreCase = true)) {
                add("X-MCP-API-Key")
            }
        }.distinctBy { it.lowercase() }
    }
}
