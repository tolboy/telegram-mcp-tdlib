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
            val code = when (error) {
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
                is TelegramApiException -> "TELEGRAM_API_ERROR"
                else -> "INTERNAL_ERROR"
            }
            val delayMs = (error as? AntiSpamException)?.retryAfterMs?.coerceAtLeast(0)
            val delaySeconds = delayMs?.let { it / 1000 + if (it % 1000 == 0L) 0 else 1 }
            val action = when (code) {
                "INVALID_INPUT" -> "Correct the arguments before retrying."
                "AUTH_REQUIRED" -> "Ask the operator to authenticate the selected account."
                "CHAT_FORBIDDEN", "ACCOUNT_FORBIDDEN", "READ_ONLY", "FILE_FORBIDDEN", "GUARDRAIL_REJECTED" ->
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
    }
}
