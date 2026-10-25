package dev.telegrammcp.server.tool

import dev.telegrammcp.server.exception.*

/** Stable recovery hints; retryable means an unchanged call may be retried safely. */
data class ToolError(
    val code: String,
    val message: String,
    val retryable: Boolean = false,
    val retry_after_seconds: Long? = null,
    val next_action: String,
) {
    companion object {
        fun from(error: Exception): ToolError {
            if (error is TelegramApiException) return fromTelegram(error)
            val code = when (error) {
                is dev.telegrammcp.server.security.PermissionDeniedException -> "PERMISSION_DENIED"
                is SendOperationConflictException -> "IDEMPOTENCY_CONFLICT"
                is SendOperationBusyException -> "OPERATION_IN_PROGRESS"
                is SendJournalException -> "SEND_JOURNAL_UNAVAILABLE"
                is SendOutcomeUnknownException -> "SEND_OUTCOME_UNKNOWN"
                is InvalidToolInputException, is IllegalArgumentException -> "INVALID_INPUT"
                is ChatNotAllowedException -> "CHAT_FORBIDDEN"
                is AccountAccessDeniedException -> "ACCOUNT_FORBIDDEN"
                is TdLibAuthException -> "AUTH_REQUIRED"
                is ReadOnlyModeException -> "READ_ONLY"
                is ConfirmationRequiredException -> "CONFIRMATION_REQUIRED"
                is ApprovalDeniedException -> "APPROVAL_DENIED"
                is ApprovalUnavailableException -> "APPROVAL_UNAVAILABLE"
                is AntiSpamException -> "RATE_LIMITED"
                is FileSecurityException -> "FILE_FORBIDDEN"
                is GuardrailViolationException -> "GUARDRAIL_REJECTED"
                is EntityNotFoundException -> "ENTITY_NOT_FOUND"
                is TelegramUnavailableException -> "TELEGRAM_UNAVAILABLE"
                else -> "INTERNAL_ERROR"
            }
            val delayMs = (error as? AntiSpamException)?.retryAfterMs?.coerceAtLeast(0)
            val delaySeconds = delayMs?.let { it / 1000 + if (it % 1000 == 0L) 0 else 1 }
            val action = when (code) {
                "IDEMPOTENCY_CONFLICT" -> "Use the original parameters to inspect the existing operation. Do not change the key merely to retry an uncertain send."
                "OPERATION_IN_PROGRESS" -> "Wait, then use get_send_operation with the same chat and key."
                "SEND_JOURNAL_UNAVAILABLE" -> "Ask the operator to inspect the journal and delivery; do not delete records or resend automatically."
                "SEND_OUTCOME_UNKNOWN" ->
                    "Check the target chat for delivery before sending again; do not automatically retry."
                "INVALID_INPUT" -> "Correct the arguments before retrying."
                "AUTH_REQUIRED" -> "Ask the operator to authenticate the selected account."
                "PERMISSION_DENIED", "CHAT_FORBIDDEN", "ACCOUNT_FORBIDDEN", "READ_ONLY", "FILE_FORBIDDEN", "GUARDRAIL_REJECTED" ->
                    "Ask the operator to review the access policy; do not bypass it."
                "CONFIRMATION_REQUIRED", "APPROVAL_DENIED", "APPROVAL_UNAVAILABLE" ->
                    "Obtain operator approval through the configured approval flow before proceeding."
                "RATE_LIMITED" -> "Review the anti-spam rejection and any previous send before retrying; " +
                    "retry_after_seconds is a minimum delay, not permission to repeat a write."
                "ENTITY_NOT_FOUND" -> "Check the chat or user identifier."
                else -> "Inspect the error and verify any side effects before retrying."
            }
            // AntiSpamException also covers duplicates of earlier sends. A finite
            // delay must not be mistaken for evidence that another write is safe.
            return ToolError(code, error.message ?: "Unknown error", false, delaySeconds, action)
        }

        /**
         * Classifies a TDLib rejection by its error code (and, for flood
         * control, its message). Telegram rejected these requests before
         * executing them, so only rate limits and server errors are retryable.
         */
        private fun fromTelegram(error: TelegramApiException): ToolError {
            val message = error.message
            return when {
                error.isRateLimit -> ToolError(
                    "RATE_LIMITED", message, retryable = true, retry_after_seconds = error.retryAfterSeconds,
                    next_action = "Telegram flood control rejected the request without executing it. Wait at least " +
                        "retry_after_seconds before retrying; retrying sooner extends the flood wait.",
                )
                error.errorCode == 401 -> ToolError(
                    "AUTH_REQUIRED", message,
                    next_action = "Ask the operator to authenticate the selected account.",
                )
                error.errorCode == 403 -> ToolError(
                    "PERMISSION_DENIED", message,
                    next_action = "The Telegram account lacks the rights for this action in this chat; " +
                        "do not retry unchanged.",
                )
                error.errorCode == 404 || (error.errorCode == 400 && isNotFound(error.errorMessage)) -> ToolError(
                    "ENTITY_NOT_FOUND", message,
                    next_action = "Check the chat, user or message identifier.",
                )
                error.errorCode == 400 -> ToolError(
                    "INVALID_INPUT", message,
                    next_action = "Telegram rejected the arguments; correct them before retrying.",
                )
                error.isServerError -> ToolError(
                    "TELEGRAM_UNAVAILABLE", message, retryable = true,
                    next_action = "Transient Telegram server error; retry after a short delay. " +
                        "For writes, check the chat for the result before sending again.",
                )
                else -> ToolError(
                    "TELEGRAM_API_ERROR", message,
                    next_action = "Inspect the error and verify any side effects before retrying.",
                )
            }
        }

        private val NOT_FOUND = Regex("""not found|_NOT_FOUND\b|\b(?:CHAT|USER|PEER|MESSAGE|CHANNEL)_ID_INVALID\b""", RegexOption.IGNORE_CASE)

        private fun isNotFound(message: String): Boolean = NOT_FOUND.containsMatchIn(message)
    }
}
