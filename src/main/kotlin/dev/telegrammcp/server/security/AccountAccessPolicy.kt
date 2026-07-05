package dev.telegrammcp.server.security

import dev.telegrammcp.server.client.TelegramAccountRegistry
import dev.telegrammcp.server.config.McpSecurityProperties
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Component
import java.util.Locale

/** Enforces API-key account scopes before an MCP tool reaches TDLib. */
@Component
class AccountAccessPolicy(
    private val registry: TelegramAccountRegistry,
    private val securityProperties: McpSecurityProperties = McpSecurityProperties(),
) {

    fun selectAccount(arguments: Map<String, Any>): String {
        val requested = arguments[ACCOUNT_ARGUMENT]?.toString()?.trim().orEmpty()
        val account = when {
            requested.isNotBlank() -> TelegramAccountRegistry.normalizeLabel(requested)
            !registry.isMultiAccount() && registry.labels().size == 1 -> registry.labels().single()
            else -> throw IllegalArgumentException(
                "'account' is required when multiple Telegram accounts are configured. " +
                    "Available accounts: ${registry.labels().joinToString()}",
            )
        }

        require(registry.has(account)) {
            "Unknown Telegram account '$account'. Available accounts: ${registry.labels().joinToString()}"
        }
        val allowed = allowedAccounts()
        require(allowed == null || account in allowed) {
            "The authenticated MCP client is not allowed to access Telegram account '$account'"
        }
        return account
    }

    fun visibleAccounts(): List<String> {
        val allowed = allowedAccounts() ?: return registry.labels()
        return registry.labels().filter { it in allowed }
    }

    private fun allowedAccounts(): Set<String>? {
        val auth = SecurityContextHolder.getContext().authentication
        return when (auth) {
            is ApiKeyAuthToken -> auth.allowedAccounts
            is JwtAuthenticationToken -> {
                val claimName = securityProperties.security.oauth.accountsClaim
                val raw = auth.token.claims[claimName] ?: return null
                val labels = when (raw) {
                    is Collection<*> -> raw.mapNotNull { it?.toString() }
                    is String -> raw.split(',').map(String::trim)
                    else -> throw IllegalArgumentException("OAuth claim '$claimName' must be a string or array")
                }.filter(String::isNotBlank)
                normalizeScopes(labels)
            }
            else -> null
        }
    }

    companion object {
        const val ACCOUNT_ARGUMENT = "account"
        const val ACCOUNT_DESCRIPTION = "Configured Telegram account label. Required when more than one account is configured."

        fun normalizeScopes(scopes: List<String>): Set<String> = scopes
            .map { TelegramAccountRegistry.normalizeLabel(it.lowercase(Locale.ROOT)) }
            .toSet()
    }
}
