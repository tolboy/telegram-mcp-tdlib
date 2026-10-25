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
            if (error is TelegramFloodGateException) return ToolError(
                FLOOD_WAIT, error.message, retryable = true, retry_after_seconds = error.retryAfterSeconds,
                next_action = FLOOD_WAIT_ACTION,
            )
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

        const val FLOOD_WAIT = "TELEGRAM_FLOOD_WAIT"
        private const val FLOOD_WAIT_ACTION = "Telegram flood control rejected the request without executing it. " +
            "Wait at least retry_after_seconds, then retry the unchanged call (for keyed sends, reuse the same " +
            "idempotency_key). Retrying sooner is rejected locally and does not reach Telegram."

        /**
         * Maps a classified TDLib rejection (see TelegramErrorClassifier). Only
         * flood waits and allow-listed transient failures of read-only requests
         * are retryable; a failed write may already have taken effect.
         */
        private fun fromTelegram(error: TelegramApiException): ToolError {
            val message = error.message
            return when (error.kind) {
                TelegramErrorKind.FLOOD_WAIT -> ToolError(
                    FLOOD_WAIT, message, retryable = true, retry_after_seconds = error.retryAfterSeconds,
                    next_action = FLOOD_WAIT_ACTION,
                )
                TelegramErrorKind.AUTH_REQUIRED -> ToolError(
                    "AUTH_REQUIRED", message,
                    next_action = "Ask the operator to authenticate the selected account.",
                )
                TelegramErrorKind.PERMISSION_DENIED -> ToolError(
                    "PERMISSION_DENIED", message,
                    next_action = "The Telegram account cannot access this chat or lacks the rights for this action; " +
                        "do not retry unchanged.",
                )
                TelegramErrorKind.NOT_FOUND -> ToolError(
                    "ENTITY_NOT_FOUND", message,
                    next_action = "Check the chat, user, message or topic identifier.",
                )
                TelegramErrorKind.INVALID_INPUT -> ToolError(
                    "INVALID_INPUT", message,
                    next_action = "Telegram rejected the arguments; correct them before retrying.",
                )
                TelegramErrorKind.CANCELLED -> ToolError(
                    "TELEGRAM_REQUEST_CANCELLED", message,
                    next_action = "The request was cancelled before completion (for example during shutdown). " +
                        "Verify any side effects before retrying a write.",
                )
                TelegramErrorKind.TRANSIENT_SERVER -> ToolError(
                    "TELEGRAM_UNAVAILABLE", message, retryable = error.readOnlyRequest,
                    next_action = if (error.readOnlyRequest) {
                        "Transient Telegram server error; retry the read after a short delay."
                    } else {
                        "Transient Telegram server error. The write may have taken effect; check the chat " +
                            "before sending again."
                    },
                )
                TelegramErrorKind.SERVER_ERROR, TelegramErrorKind.UNKNOWN -> ToolError(
                    "TELEGRAM_API_ERROR", message,
                    next_action = "Inspect the error and verify any side effects before retrying.",
                )
            }
        }
    }
}
