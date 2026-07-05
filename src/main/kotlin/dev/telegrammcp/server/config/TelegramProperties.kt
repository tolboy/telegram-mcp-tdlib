package dev.telegrammcp.server.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

/**
 * Typed configuration for Telegram connectivity and security.
 *
 * Populated from `telegram.*` keys in application.yml or environment variables.
 * The bot token is optional — when TDLib is configured (via [TdLibProperties]),
 * it takes precedence. The [security] section applies to both client modes.
 */
@Validated
@ConfigurationProperties(prefix = "telegram")
data class TelegramProperties(
    val bot: BotProperties = BotProperties(),
    val api: ApiProperties = ApiProperties(),
    val security: SecurityProperties = SecurityProperties(),
    /**
     * Optional isolated TDLib accounts. Map keys are stable public labels such
     * as `work` or `personal`; values never need to contain account names.
     */
    val accounts: Map<String, AccountProperties> = emptyMap(),
) {
    data class BotProperties(
        /** Bot token from @BotFather. Optional when using TDLib user-account mode. */
        val token: String = "",
        val username: String = "",
    )

    data class ApiProperties(
        val baseUrl: String = "https://api.telegram.org",
        val timeout: Duration = Duration.ofSeconds(30),
        val maxRetries: Int = 3,
    )

    data class SecurityProperties(
        /** Comma-separated allowed chat IDs; empty means all chats allowed. */
        val allowedChatIds: List<Long> = emptyList(),
    )

    /** Per-account TDLib configuration for multi-account deployments. */
    data class AccountProperties(
        val api: TdLibProperties.ApiCredentials = TdLibProperties.ApiCredentials(),
        val auth: TdLibProperties.Auth = TdLibProperties.Auth(),
        val database: TdLibProperties.Database = TdLibProperties.Database(),
        val session: TdLibProperties.Session = TdLibProperties.Session(),
        /** Optional per-account override for TDLib's outbound proxy. */
        val proxy: TdLibProperties.Proxy = TdLibProperties.Proxy(),
        val logVerbosityLevel: Int? = null,
    )
}
