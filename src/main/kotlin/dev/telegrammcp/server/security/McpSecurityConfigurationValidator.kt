package dev.telegrammcp.server.security

import dev.telegrammcp.server.config.McpAuthMode
import dev.telegrammcp.server.config.McpSecurityProperties
import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Component
import org.springframework.context.annotation.Profile
import java.net.URI

/** Fails closed when API-key and OAuth configuration are mixed or incomplete. */
@Component
@Profile("!stdio")
class McpSecurityConfigurationValidator(
    private val properties: McpSecurityProperties,
) {

    @PostConstruct
    fun validate() {
        val security = properties.security
        val oauth = security.oauth
        when (security.mode) {
            McpAuthMode.API_KEY -> require(
                oauth.issuerUri.isBlank() &&
                    oauth.jwkSetUri.isBlank() &&
                    oauth.resourceUri.isBlank(),
            ) {
                "OAuth settings require MCP_AUTH_MODE=oauth"
            }
            McpAuthMode.OAUTH -> {
                require(
                    security.apiKey.isBlank() &&
                        security.apiKeyFile.isBlank() &&
                        security.clients.isEmpty(),
                ) {
                    "API keys and OAuth are mutually exclusive; remove MCP_API_KEY/client keys in OAuth mode"
                }
                require(oauth.issuerUri.isNotBlank()) { "OAuth mode requires MCP_OAUTH_ISSUER_URI" }
                require(oauth.resourceUri.isNotBlank()) { "OAuth mode requires MCP_OAUTH_RESOURCE_URI" }
                requireHttpsUri(oauth.issuerUri, "MCP_OAUTH_ISSUER_URI")
                requireHttpsUri(oauth.resourceUri, "MCP_OAUTH_RESOURCE_URI")
                if (oauth.jwkSetUri.isNotBlank()) requireHttpsUri(oauth.jwkSetUri, "MCP_OAUTH_JWK_SET_URI")
                require(oauth.accountsClaim.isNotBlank()) { "OAuth accounts claim must not be blank" }
                require(oauth.principalClaim.isNotBlank()) { "OAuth principal claim must not be blank" }
            }
        }
    }

    private fun requireHttpsUri(raw: String, name: String) {
        val uri = runCatching { URI(raw) }.getOrNull()
        require(uri?.scheme.equals("https", ignoreCase = true) && !uri?.host.isNullOrBlank()) {
            "$name must be an absolute HTTPS URI"
        }
    }
}
