package dev.telegrammcp.server.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/**
 * Typed configuration for TDLib (Telegram Database Library) connectivity.
 *
 * Populated from `tdlib.*` keys in application.yml or environment variables
 * (`TDLIB_API_ID`, `TDLIB_API_HASH`, `TDLIB_PHONE_NUMBER`, etc.).
 */
@Validated
@ConfigurationProperties(prefix = "tdlib")
data class TdLibProperties(
    val api: ApiCredentials = ApiCredentials(),
    val auth: Auth = Auth(),
    val database: Database = Database(),
    val session: Session = Session(),
    /** Optional TDLib-native proxy. Blank [Proxy.type] means direct networking. */
    val proxy: Proxy = Proxy(),
    /**
     * TDLib native log verbosity (0–10). 0 = fatal only, 1 = errors, 2 = warnings,
     * 3 = info, 5 = default, >5 = debug. Applied via `Log.setVerbosityLevel` at startup.
     */
    val logVerbosityLevel: Int = 1,
) {
    /** Telegram API credentials from https://my.telegram.org */
    data class ApiCredentials(
        val id: Int = 0,
        val hash: String = "",
        /** Mounted secret-file alternative to [hash]; the two forms are mutually exclusive. */
        val hashFile: String = "",
    )

    /** Authentication mode — phone/bot are optional when a QR-authenticated session is already persisted. */
    data class Auth(
        /** Phone number in international format (+1234567890) for user-account mode. */
        val phoneNumber: String = "",
        /** Bot token from @BotFather for bot mode. */
        val botToken: String = "",
        /** Mounted secret-file alternative to [botToken]. */
        val botTokenFile: String = "",
        /** 2FA password (if enabled on the account). Required for non-interactive startup. */
        val password: String = "",
        /** Mounted secret-file alternative to [password]. */
        val passwordFile: String = "",
        /** One-time login code. Used once; wipe after successful auth. */
        val code: String = "",
        /** Mounted secret-file alternative to [code]. */
        val codeFile: String = "",
    )

    /**
     * TDLib local database settings. These map directly to `TDLibSettings` setters in
     * tdlight-java; disabling any flag reduces on-disk footprint at the cost of some features.
     *
     * Note: tdlight's `SimpleTelegramClient` does NOT expose the database encryption key —
     * encryption is a TDLib-parameter-level concern handled inside the native layer and cannot
     * be toggled from Java without a custom client. If you need at-rest encryption, rely on
     * filesystem-level encryption (LUKS, FileVault, EFS) for `database.directory`.
     */
    data class Database(
        /** Directory for TDLib session data. Persisted between restarts. */
        val directory: String = "",
        /** Where downloaded media is cached. Defaults to `{directory}/downloads`. */
        val downloadsDirectory: String = "",
        /** Persist file metadata (required for resumable downloads and media access by id). */
        val useFileDatabase: Boolean = true,
        /** Cache chat/user info locally to reduce `GetChat`/`GetUser` round-trips. */
        val useChatInfoDatabase: Boolean = true,
        /** Persist message history locally — required for most MCP history/search tools. */
        val useMessageDatabase: Boolean = true,
    )

    /** Session and network settings. */
    data class Session(
        /** Use Telegram test DC (for development). */
        val useTestDc: Boolean = false,
        val systemLanguageCode: String = "en",
        val deviceModel: String = "Telegram MCP Server",
        val applicationVersion: String = "dev",
    )

    /**
     * A single outbound proxy for one TDLib account. Supported values for
     * [type] are `socks5`, `http` (HTTP CONNECT), and `mtproto`.
     */
    data class Proxy(
        val type: String = "",
        val server: String = "",
        val port: Int = 0,
        val username: String = "",
        /** Secret-file alternative to [password]. */
        val password: String = "",
        val passwordFile: String = "",
        /** For an HTTP proxy, restrict it to HTTP requests instead of all TDLib traffic. */
        val httpOnly: Boolean = false,
        /** MTProto secret. It is required only when [type] is `mtproto`. */
        val secret: String = "",
        val secretFile: String = "",
    ) {
        fun isEmpty(): Boolean =
            server.isBlank() && port == 0 && username.isBlank() && password.isBlank() && passwordFile.isBlank() &&
                !httpOnly && secret.isBlank() && secretFile.isBlank()
    }

    /** Whether TDLib is configured (api credentials are present). */
    @Suppress("unused")
    val isConfigured: Boolean
        get() = api.id > 0 && api.hash.isNotBlank()

    /** Whether we're in user-account mode (vs bot mode). */
    val isUserMode: Boolean
        get() = auth.phoneNumber.isNotBlank()
}
