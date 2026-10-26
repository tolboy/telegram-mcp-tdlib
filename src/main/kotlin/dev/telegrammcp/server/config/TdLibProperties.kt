package dev.telegrammcp.server.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

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
    /** Timeouts, the per-tool-call deadline and the concurrency limit for TDLib requests. */
    val requests: Requests = Requests(),
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
        /**
         * How long a tool call waits for the account to finish authenticating.
         *
         * Startup never waits — that would delay the MCP `initialize` response
         * past the handshake timeout of a stdio client (60 s in Claude Desktop)
         * and the connector would be dropped. The wait happens on the first
         * tool call instead, so keep this comfortably below the client's limit.
         */
        val readyTimeout: Duration = Duration.ofSeconds(45),
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

    /**
     * Limits for TDLib requests made by tool calls.
     *
     * MCP clients drop a tool call after about 60 s (the TypeScript MCP SDK
     * default and Claude Desktop), so [toolDeadline] caps the whole chain of
     * TDLib waits in one tool call (rate limiter, request, final message state,
     * title lookups) and must stay below the client's limit. Each wait is the
     * smaller of its own timeout and the time left in the deadline.
     */
    data class Requests(
        /** Wait for one TDLib response. */
        val timeout: Duration = Duration.ofSeconds(40),
        /** Wait for updateMessageSendSucceeded/Failed after TDLib accepted a send. */
        val messageSendTimeout: Duration = Duration.ofSeconds(40),
        /**
         * Wait for a synchronous DownloadFile; its timeouts never count toward the circuit breaker.
         * In a tool call the wait is also capped by the time left in [toolDeadline].
         */
        val downloadTimeout: Duration = Duration.ofSeconds(45),
        /** Overall budget for the TDLib work of one tool call. */
        val toolDeadline: Duration = Duration.ofSeconds(50),
        /**
         * After a local timeout the request may still be parked in TDLib's
         * flood-wait delayer; further requests in the same gate scope fail fast
         * for this long instead of queueing behind it.
         */
        val localTimeoutGate: Duration = Duration.ofSeconds(10),
        /**
         * Per-account bulkhead: concurrent TDLib operations beyond this fail fast
         * with TELEGRAM_BUSY instead of holding server threads while they queue.
         */
        val maxConcurrentCalls: Int = 25,
        /**
         * While TDLib reports `ConnectionStateReady`, a full request timeout
         * counts toward the circuit breaker only if the timeouts since TDLib
         * last answered came from at least this many flood-gate scopes...
         */
        val breakerTimeoutScopes: Int = 3,
        /** ...or if TDLib answered no request for this long (see TdLibTimeoutHealth). */
        val breakerNoAnswerWindow: Duration = Duration.ofSeconds(180),
    ) {
        init {
            listOf(timeout, messageSendTimeout, downloadTimeout, toolDeadline).forEach {
                require(!it.isNegative && !it.isZero) { "tdlib.requests timeouts must be positive" }
            }
            // A wait longer than the deadline could never run to its own end, so a
            // timeout that cannot fire would silently turn into a deadline cut.
            require(toolDeadline > timeout && toolDeadline > messageSendTimeout) {
                "tdlib.requests.tool-deadline ($toolDeadline) must be longer than tdlib.requests.timeout " +
                    "($timeout) and tdlib.requests.message-send-timeout ($messageSendTimeout); raise " +
                    "TDLIB_TOOL_DEADLINE or lower TDLIB_REQUEST_TIMEOUT / TDLIB_MESSAGE_SEND_TIMEOUT"
            }
            require(!localTimeoutGate.isNegative) { "tdlib.requests.local-timeout-gate must not be negative" }
            require(maxConcurrentCalls > 0) { "tdlib.requests.max-concurrent-calls must be positive" }
            require(breakerTimeoutScopes > 0) { "tdlib.requests.breaker-timeout-scopes must be positive" }
            require(!breakerNoAnswerWindow.isNegative && !breakerNoAnswerWindow.isZero) {
                "tdlib.requests.breaker-no-answer-window must be positive"
            }
        }
    }

    /** Whether TDLib is configured (api credentials are present). */
    @Suppress("unused")
    val isConfigured: Boolean
        get() = api.id > 0 && api.hash.isNotBlank()

    /** Whether we're in user-account mode (vs bot mode). */
    val isUserMode: Boolean
        get() = auth.phoneNumber.isNotBlank()
}
