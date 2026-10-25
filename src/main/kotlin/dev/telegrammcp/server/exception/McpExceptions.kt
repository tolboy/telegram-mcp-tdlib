package dev.telegrammcp.server.exception

/**
 * Base sealed hierarchy for all MCP-server errors.
 *
 * Using a sealed class lets the compiler verify exhaustive `when` branches
 * and keeps error taxonomy in one place.
 */
sealed class McpException(
    override val message: String,
    override val cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * The Telegram API (TDLib) rejected a request with a TDLib `error` object.
 *
 * TDLib follows HTTP-like codes: 400 bad request, 401 unauthorized,
 * 403 forbidden, 404 not found, 420/429 flood control and 5xx internal
 * errors. Flood waits are reported either as `FLOOD_WAIT_N` /
 * `FLOOD_PREMIUM_WAIT_N` (usually code 420) or as
 * `Too Many Requests: retry after N` (code 429).
 */
class TelegramApiException(
    val errorCode: Int,
    val errorMessage: String,
    cause: Throwable? = null,
) : McpException("Telegram API error ($errorCode): $errorMessage", cause) {

    /** Minimum delay Telegram asked for, parsed from the error message. */
    val retryAfterSeconds: Long? = parseRetryAfterSeconds(errorMessage)

    /** Flood control: the request was not executed; retry after [retryAfterSeconds]. */
    val isRateLimit: Boolean
        get() = errorCode == FLOOD_CODE || errorCode == TOO_MANY_REQUESTS_CODE ||
            FLOOD_WAIT.containsMatchIn(errorMessage)

    /**
     * A 4xx rejection caused by the request itself (bad arguments, missing
     * rights, unknown chat). It says nothing about Telegram's health. Rate
     * limits are 4xx too but are excluded: they signal real back-pressure.
     */
    val isCallerError: Boolean
        get() = errorCode in 400..499 && !isRateLimit

    /** TDLib or Telegram server-side failure (5xx). */
    val isServerError: Boolean
        get() = errorCode in 500..599

    companion object {
        const val FLOOD_CODE = 420
        const val TOO_MANY_REQUESTS_CODE = 429

        private val FLOOD_WAIT = Regex("""\bFLOOD_(?:PREMIUM_)?WAIT_(\d+)\b""")
        private val RETRY_AFTER = Regex("""\bretry after (\d+)\b""", RegexOption.IGNORE_CASE)

        fun parseRetryAfterSeconds(message: String): Long? =
            (FLOOD_WAIT.find(message) ?: RETRY_AFTER.find(message))
                ?.groupValues?.get(1)?.toLongOrNull()
    }
}

/** The caller supplied invalid or missing tool arguments. */
class InvalidToolInputException(
    message: String,
) : McpException(message)

/** A guardrail check rejected the request (e.g. prompt injection detected). */
class GuardrailViolationException(
    message: String,
) : McpException("Guardrail violation: $message")

/** The requested chat is not in the allow-list. */
class ChatNotAllowedException(
    chatId: Long? = null,
) : McpException(
    chatId?.let { "Access to chat $it is not allowed by security policy" }
        ?: "Access to the resolved chat is not allowed by security policy",
)

/** The authenticated MCP client is not permitted to route calls to an account. */
class AccountAccessDeniedException(
    account: String,
) : McpException("The authenticated MCP client is not allowed to access Telegram account '$account'")

/** The Telegram circuit breaker is open — service temporarily unavailable. */
class TelegramUnavailableException(
    cause: Throwable? = null,
) : McpException("Telegram API is temporarily unavailable", cause)

/** A Telegram entity (user, chat, channel) could not be resolved. */
class EntityNotFoundException(
    identifier: String,
) : McpException("Telegram entity not found: $identifier")

/** TDLib authentication failed or is incomplete. */
class TdLibAuthException(
    message: String,
    cause: Throwable? = null,
) : McpException("TDLib authentication error: $message", cause)

/** A file path failed security validation. */
class FileSecurityException(
    message: String,
) : McpException("File security violation: $message")

/** A write operation was blocked because the server is in read-only mode. */
class ReadOnlyModeException(
    toolName: String,
) : McpException("Tool '$toolName' is blocked: server is in read-only mode")

/**
 * A destructive operation requires an explicit caller acknowledgement.
 *
 * The MCP server cannot prove that the acknowledgement originated from a
 * human; an MCP host remains responsible for any human-approval UX.
 */
class ConfirmationRequiredException(
    toolName: String,
    description: String,
) : McpException(
    "Tool '$toolName' requires caller acknowledgement: $description. " +
        "Re-invoke with \"confirmed\": true only after any required human approval.",
)

/**
 * A human declined a destructive operation, or the approval never arrived.
 *
 * Unlike [ConfirmationRequiredException] this is not something the caller can
 * satisfy by adding an argument: the answer came from the person operating the
 * MCP host, over a channel the model does not control.
 */
class ApprovalDeniedException(
    toolName: String,
    reason: String,
) : McpException("Tool '$toolName' was not approved: $reason.")

/**
 * Approval is required but this client cannot ask anyone for it.
 *
 * Failing closed is the point: silently downgrading to the caller-asserted
 * `confirmed: true` would give the operator an approval guarantee the session
 * cannot actually provide.
 */
class ApprovalUnavailableException(
    toolName: String,
    reason: String = "this MCP client did not advertise the elicitation capability",
    remediation: String =
        "Connect a client that supports MCP elicitation, or set MCP_DESTRUCTIVE_APPROVAL=auto " +
            "to use the secure loopback fallback.",
    cause: Throwable? = null,
) : McpException(
    "Tool '$toolName' requires human approval, but $reason. $remediation " +
        "MCP_DESTRUCTIVE_APPROVAL=off disables human approval and is unsafe for destructive tools.",
    cause,
)

/**
 * The anti-spam guard rejected the call (rate limit, daily cap, or duplicate).
 *
 * [retryAfterMs] is the earliest timestamp delta after which a retry might
 * succeed; callers can surface this to humans. `null` means "next day" for
 * daily-cap rejections.
 */
class AntiSpamException(
    toolName: String,
    val reason: String,
    val retryAfterMs: Long? = null,
) : McpException(
    "Anti-spam guard blocked '$toolName': $reason" +
        (retryAfterMs?.let { ". Retry after ${it / 1000}s" } ?: ""),
)
