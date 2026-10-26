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
 * [kind] comes from [TelegramErrorClassifier], which combines the HTTP-like
 * code with explicit tables of TDLib error strings. Flood control normally
 * reaches us only as `429 Too Many Requests: retry after N`: TDLib itself waits
 * out 420 `FLOOD_WAIT_N` / `SLOWMODE_WAIT_N` and gives up with 429 once its
 * total wait limit is exceeded (td/telegram/net/NetQueryDelayer.cpp).
 *
 * [readOnlyRequest] is true when the failed TDLib function has no side
 * effects, so an unchanged retry after a transient failure is safe.
 */
class TelegramApiException(
    val errorCode: Int,
    val errorMessage: String,
    cause: Throwable? = null,
    val readOnlyRequest: Boolean = false,
) : McpException("Telegram API error ($errorCode): $errorMessage", cause) {

    val kind: TelegramErrorKind = TelegramErrorClassifier.classify(errorCode, errorMessage)

    /** Minimum delay Telegram asked for, parsed from the error message. */
    val retryAfterSeconds: Long? = TelegramErrorClassifier.parseRetryAfterSeconds(errorMessage)

    /** Flood control: the request was not executed; retry after [retryAfterSeconds]. */
    val isFloodWait: Boolean get() = kind == TelegramErrorKind.FLOOD_WAIT

    /** True when Telegram definitely did not execute the request (safe to record as rejected). */
    val wasNotExecuted: Boolean get() = isFloodWait

    /** True when the failure says something about Telegram's health and should count toward the breaker. */
    val countsTowardBreaker: Boolean get() = kind.countsTowardBreaker

    /** Retrying the unchanged call may succeed and cannot duplicate a side effect. */
    val isRetryable: Boolean
        get() = isFloodWait || (kind == TelegramErrorKind.TRANSIENT_SERVER && readOnlyRequest)

    companion object {
        fun parseRetryAfterSeconds(message: String): Long? = TelegramErrorClassifier.parseRetryAfterSeconds(message)
    }
}

/**
 * A local per-chat (or per-method) flood gate rejected the call without sending
 * it, because Telegram recently returned a flood wait for the same scope.
 */
class TelegramFloodGateException(
    val scope: String,
    val retryAfterSeconds: Long,
) : McpException(
    "Telegram flood wait still active for $scope; retry after ${retryAfterSeconds}s (request not sent)",
)

/**
 * A forward batch hit flood control after some of its messages had already
 * been delivered. Unlike a plain flood wait this is not "nothing happened":
 * retrying the unchanged call would duplicate [deliveredMessageIds], so it is
 * never retryable. [deliveredMessageIds] and [unconfirmedMessageIds] are source
 * message IDs (the caller's message_ids), in request order.
 */
class ForwardPartiallyDeliveredException(
    val toChatId: Long,
    val deliveredMessageIds: List<Long>,
    val unconfirmedMessageIds: List<Long>,
    val retryAfterSeconds: Long?,
    cause: TelegramApiException,
) : McpException(
    "Telegram flood control stopped forwarding to chat $toChatId after ${deliveredMessageIds.size} of " +
        "${deliveredMessageIds.size + unconfirmedMessageIds.size} messages were delivered " +
        "(delivered message_ids: $deliveredMessageIds; not confirmed: $unconfirmedMessageIds)",
    cause,
)

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
