package dev.telegrammcp.server.controller

import dev.telegrammcp.server.config.McpSecurityProperties
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/** RFC 9728 metadata used by MCP clients to discover the external issuer. */
@RestController
@ConditionalOnProperty(prefix = "mcp.security", name = ["mode"], havingValue = "oauth")
class OAuthProtectedResourceController(
    private val properties: McpSecurityProperties,
) {

    @GetMapping("/.well-known/oauth-protected-resource")
    fun metadata(): Map<String, Any> = mapOf(
        "resource" to properties.security.oauth.resourceUri,
        "authorization_servers" to listOf(properties.security.oauth.issuerUri),
        "bearer_methods_supported" to listOf("header"),
        "scopes_supported" to listOf("mcp:tools"),
    )
}
