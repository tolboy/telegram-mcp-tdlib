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
 * A local flood gate rejected the call without sending it, because Telegram
 * recently returned a flood wait for the same scope or, when
 * [afterLocalTimeout] is true, because an earlier request in the scope got no
 * answer in time (TDLib may still be waiting out flood control for it).
 */
class TelegramFloodGateException(
    val scope: String,
    val retryAfterSeconds: Long,
    val afterLocalTimeout: Boolean = false,
) : McpException(
    if (afterLocalTimeout) {
        "An earlier Telegram request for $scope did not complete in time (TDLib may still be waiting out " +
            "flood control); retry after ${retryAfterSeconds}s (request not sent)"
    } else {
        "Telegram flood wait still active for $scope; retry after ${retryAfterSeconds}s (request not sent)"
    },
)

/**
 * A forward batch hit flood control after some of its messages had already
 * been delivered. Unlike a plain flood wait this is not "nothing happened":
 * retrying the unchanged call would duplicate [deliveredMessageIds], so it is
 * never retryable. All lists hold source message IDs (the caller's
 * message_ids) in request order:
 * - [deliveredMessageIds]: delivered to the destination;
 * - [unconfirmedMessageIds]: accepted by TDLib but not confirmed when the
 *   flood wait hit (check the chat; forward only the missing ones later);
 * - [notForwardableMessageIds]: TDLib could not forward them at all (it
 *   returned no message for them, e.g. protected content or deleted
 *   messages); retrying them will not help.
 */
class ForwardPartiallyDeliveredException(
    val toChatId: Long,
    val deliveredMessageIds: List<Long>,
    val unconfirmedMessageIds: List<Long>,
    val retryAfterSeconds: Long?,
    cause: TelegramApiException,
    val notForwardableMessageIds: List<Long> = emptyList(),
) : McpException(
    "Telegram flood control stopped forwarding to chat $toChatId after ${deliveredMessageIds.size} of " +
        "${deliveredMessageIds.size + unconfirmedMessageIds.size + notForwardableMessageIds.size} messages were " +
        "delivered (delivered message_ids: $deliveredMessageIds; not confirmed: $unconfirmedMessageIds" +
        (if (notForwardableMessageIds.isEmpty()) "" else "; not forwardable: $notForwardableMessageIds") + ")",
    cause,
)

/**
 * No TDLib answer arrived in time for a request other than a message send
 * (sends report [SendOutcomeUnknownException] instead).
 *
 * [reason] says which limit ended the wait; only [Reason.REQUEST_TIMEOUT]
 * says something about Telegram's health and counts toward the circuit
 * breaker. [requestSent] is false when the deadline ran out before the request
 * was handed to TDLib. [retryable] is true only when no write was handed to
 * TDLib during this tool call, so an unchanged retry cannot repeat a side
 * effect.
 *
 * [healthFailure] is cleared by the client service when the timeout is not
 * evidence of an unhealthy TDLib (see TdLibTimeoutHealth): for example one
 * flood-waited scope while the connection is Ready and other requests are
 * answered. [retryAfterSeconds] is the remaining local gate of the request's
 * scope when the request was sent.
 */
class TelegramTimeoutException(
    val request: String,
    val reason: Reason,
    val requestSent: Boolean,
    val retryable: Boolean,
    val waitedMillis: Long,
    cause: Throwable? = null,
) : McpException(
    when (reason) {
        Reason.REQUEST_TIMEOUT -> "Telegram did not answer $request within ${waitedMillis / 1000}s"
        Reason.DOWNLOAD_TIMEOUT -> "Telegram file download ($request) did not finish within ${waitedMillis / 1000}s"
        Reason.TOOL_DEADLINE -> if (requestSent) {
            "The tool call's overall deadline ran out while waiting for $request"
        } else {
            "The tool call's overall deadline ran out before $request was sent (request not sent)"
        }
    },
    cause,
) {
    enum class Reason { REQUEST_TIMEOUT, DOWNLOAD_TIMEOUT, TOOL_DEADLINE }

    @Volatile
    var healthFailure: Boolean = true
        internal set

    @Volatile
    var retryAfterSeconds: Long? = null
        internal set

    val countsTowardBreaker: Boolean get() = reason == Reason.REQUEST_TIMEOUT && healthFailure

    /**
     * The wait advertised to MCP and REST callers: none when an unchanged retry
     * is not safe; 1 s when nothing was sent; otherwise the remaining local gate
     * of the request's scope (a sooner retry is rejected locally).
     */
    val retryAfterHint: Long?
        get() = when {
            !retryable -> null
            !requestSent -> 1L
            else -> retryAfterSeconds ?: 1L
        }
}

/**
 * The per-account bulkhead is full: too many Telegram operations of this
 * account are already in flight. Nothing was sent for this operation.
 */
class TelegramBusyException(
    val account: String,
    val maxConcurrentCalls: Int,
    val retryable: Boolean,
) : McpException(
    "Too many concurrent Telegram operations for account '$account' (limit $maxConcurrentCalls); " +
        "request not sent",
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
