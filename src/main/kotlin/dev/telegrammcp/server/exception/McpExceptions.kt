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

/** The Telegram API (TDLib) returned a non-OK response. */
class TelegramApiException(
    errorCode: Int,
    message: String,
    cause: Throwable? = null,
) : McpException("Telegram API error ($errorCode): $message", cause)

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
    chatId: Long,
) : McpException("Access to chat $chatId is not allowed by security policy")

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

/** A destructive operation requires user confirmation before execution. */
class ConfirmationRequiredException(
    toolName: String,
    description: String,
) : McpException(
    "Tool '$toolName' requires confirmation: $description. Re-invoke with \"confirmed\": true to proceed.",
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

