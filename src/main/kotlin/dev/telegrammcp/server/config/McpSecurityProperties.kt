package dev.telegrammcp.server.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration for MCP endpoint security and guardrails.
 */
@ConfigurationProperties(prefix = "mcp")
data class McpSecurityProperties(
    val security: SecurityProps = SecurityProps(),
    val guardrails: GuardrailProps = GuardrailProps(),
    /** Curated MCP surface for a specific user job; ALL exposes every tool. */
    val toolProfile: McpToolProfile = McpToolProfile.ALL,
    /** Optional exact-name allow-list, applied after [toolProfile]. Empty means all profile tools. */
    val toolAllow: List<String> = emptyList(),
    /** Optional exact-name deny-list, applied after [toolAllow]. */
    val toolDeny: List<String> = emptyList(),
) {
    val authenticationConfigured: Boolean
        get() = security.apiKey.isNotBlank() ||
            security.apiKeyFile.isNotBlank() ||
            security.clients.isNotEmpty()

    data class SecurityProps(
        /** Transport authentication mode. OAuth is opt-in; API key remains the local-first default. */
        val mode: McpAuthMode = McpAuthMode.API_KEY,
        /** API key required for all MCP endpoints. */
        val apiKey: String = "",
        /** Docker/Podman/Kubernetes secret-file alternative to [apiKey]. */
        val apiKeyFile: String = "",
        /** Header carrying the key: `Authorization` (Bearer) or `X-MCP-API-Key`. */
        val headerName: String = "Authorization",
        /**
         * Optional named keys with account scopes. Use one key per MCP client
         * when several Telegram accounts are configured.
         */
        val clients: List<ClientKeyProps> = emptyList(),
        val oauth: OAuthProps = OAuthProps(),
    )

    data class OAuthProps(
        /** External OAuth/OIDC issuer. This server never issues tokens. */
        val issuerUri: String = "",
        /** Optional explicit JWK Set URL; blank uses issuer discovery. */
        val jwkSetUri: String = "",
        /** Required RFC 8707 resource/audience URI for this MCP server. */
        val resourceUri: String = "",
        /** JWT claim containing exact Telegram account labels. Missing means all configured labels. */
        val accountsClaim: String = "telegram_accounts",
        /** JWT claim used as the authenticated principal. */
        val principalClaim: String = "sub",
    )

    data class ClientKeyProps(
        val id: String = "",
        val apiKey: String = "",
        val apiKeyFile: String = "",
        /** Empty means all configured accounts for this key. */
        val allowedAccounts: List<String> = emptyList(),
    )

    data class GuardrailProps(
        val maxToolInputLength: Int = 4096,
        val blockedPatterns: List<String> = emptyList(),
    )
}

enum class McpAuthMode {
    API_KEY,
    OAUTH,
}

/** Curated MCP tool surfaces for common Telegram workflows. */
enum class McpToolProfile {
    /** Every registered tool, subject only to the normal server-mode policy. */
    ALL,
    /** Read-only discovery across the account; never advertises write tools. */
    READER,
    /** Personal inbox, messages, drafts, media, contacts, and privacy controls. */
    INBOX,
    /** Group/channel moderation, membership, permissions, and bot management. */
    COMMUNITY_ADMIN,
    /** Read-only public and account research without communication tools. */
    RESEARCH,
}
