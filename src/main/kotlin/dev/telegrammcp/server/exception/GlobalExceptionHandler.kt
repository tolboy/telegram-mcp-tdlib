package dev.telegrammcp.server.exception

import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Translates domain exceptions into RFC-9457 Problem Detail responses.
 */
@RestControllerAdvice
@Suppress("unused")
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    @ExceptionHandler(InvalidToolInputException::class)
    fun handleInvalidInput(ex: InvalidToolInputException): ProblemDetail {
        log.warn("Invalid tool input: {}", ex.message)
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.message)
    }

    @ExceptionHandler(GuardrailViolationException::class)
    fun handleGuardrail(ex: GuardrailViolationException): ProblemDetail {
        log.warn("Guardrail triggered: {}", ex.message)
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.message)
    }

    @ExceptionHandler(ChatNotAllowedException::class)
    fun handleChatNotAllowed(ex: ChatNotAllowedException): ProblemDetail {
        log.warn("Chat access denied: {}", ex.message)
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.message)
    }

    /**
     * Maps by [TelegramErrorKind] so REST clients see caller errors as 4xx
     * (logged at warn, without a stack trace) and only Telegram-side failures
     * as 5xx.
     */
    @ExceptionHandler(TelegramApiException::class)
    fun handleTelegramApi(ex: TelegramApiException): ResponseEntity<ProblemDetail> {
        val status = statusFor(ex.kind)
        if (status.is4xxClientError) {
            log.warn("Telegram API rejected request ({}): {}", ex.kind, ex.message)
        } else {
            log.error("Telegram API failure ({}): {}", ex.kind, ex.message, ex)
        }
        val problem = ProblemDetail.forStatusAndDetail(status, ex.message)
        problem.setProperty("telegram_error_code", ex.errorCode)
        problem.setProperty("telegram_error_kind", ex.kind.name)
        return withRetryAfter(problem, status, ex.retryAfterSeconds.takeIf { ex.isFloodWait && ex.isRetryable })
    }

    @ExceptionHandler(TelegramFloodGateException::class)
    fun handleFloodGate(ex: TelegramFloodGateException): ResponseEntity<ProblemDetail> {
        log.warn("Telegram flood wait active: {}", ex.message)
        val problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, ex.message)
        return withRetryAfter(problem, HttpStatus.TOO_MANY_REQUESTS, ex.retryAfterSeconds.takeIf { ex.isRetryable })
    }

    /** A local wait ran out; 504 because the server is a gateway to Telegram. */
    @ExceptionHandler(TelegramTimeoutException::class)
    fun handleTimeout(ex: TelegramTimeoutException): ResponseEntity<ProblemDetail> {
        log.warn("Telegram request timed out ({}): {}", ex.reason, ex.message)
        val problem = ProblemDetail.forStatusAndDetail(HttpStatus.GATEWAY_TIMEOUT, ex.message)
        problem.setProperty("timeout_reason", ex.reason.name)
        problem.setProperty("request_sent", ex.requestSent)
        return withRetryAfter(problem, HttpStatus.GATEWAY_TIMEOUT, ex.retryAfterHint)
    }

    /** The per-account bulkhead is full; nothing was sent. */
    @ExceptionHandler(TelegramBusyException::class)
    fun handleBusy(ex: TelegramBusyException): ResponseEntity<ProblemDetail> {
        log.warn("Telegram account busy: {}", ex.message)
        val problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.message)
        return withRetryAfter(problem, HttpStatus.SERVICE_UNAVAILABLE, 1L.takeIf { ex.retryable })
    }

    private fun withRetryAfter(problem: ProblemDetail, status: HttpStatus, retryAfterSeconds: Long?): ResponseEntity<ProblemDetail> {
        val builder = ResponseEntity.status(status)
        if (retryAfterSeconds != null) {
            problem.setProperty("retry_after_seconds", retryAfterSeconds)
            builder.header(HttpHeaders.RETRY_AFTER, retryAfterSeconds.toString())
        }
        return builder.body(problem)
    }

    internal fun statusFor(kind: TelegramErrorKind): HttpStatus = when (kind) {
        TelegramErrorKind.FLOOD_WAIT -> HttpStatus.TOO_MANY_REQUESTS
        TelegramErrorKind.PERMISSION_DENIED -> HttpStatus.FORBIDDEN
        TelegramErrorKind.NOT_FOUND -> HttpStatus.NOT_FOUND
        TelegramErrorKind.INVALID_INPUT -> HttpStatus.BAD_REQUEST
        // Telegram 401 concerns the server's account session, not the REST caller.
        TelegramErrorKind.AUTH_REQUIRED,
        TelegramErrorKind.CANCELLED,
        TelegramErrorKind.TRANSIENT_SERVER -> HttpStatus.SERVICE_UNAVAILABLE
        TelegramErrorKind.SERVER_ERROR,
        TelegramErrorKind.UNKNOWN -> HttpStatus.BAD_GATEWAY
    }

    @ExceptionHandler(TelegramUnavailableException::class)
    fun handleUnavailable(ex: TelegramUnavailableException): ProblemDetail {
        log.error("Telegram unavailable: {}", ex.message, ex)
        return ProblemDetail.forStatusAndDetail(
            HttpStatus.SERVICE_UNAVAILABLE,
            ex.message,
        )
    }

    @ExceptionHandler(EntityNotFoundException::class)
    fun handleEntityNotFound(ex: EntityNotFoundException): ProblemDetail {
        log.warn("Entity not found: {}", ex.message)
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.message)
    }

    @ExceptionHandler(TdLibAuthException::class)
    fun handleTdLibAuth(ex: TdLibAuthException): ProblemDetail {
        log.error("TDLib auth failure: {}", ex.message, ex)
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.message)
    }

    @ExceptionHandler(FileSecurityException::class)
    fun handleFileSecurity(ex: FileSecurityException): ProblemDetail {
        log.warn("File security violation: {}", ex.message)
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.message)
    }

    @ExceptionHandler(ReadOnlyModeException::class)
    fun handleReadOnly(ex: ReadOnlyModeException): ProblemDetail {
        log.warn("Read-only mode block: {}", ex.message)
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.message)
    }

    @ExceptionHandler(ConfirmationRequiredException::class)
    fun handleConfirmation(ex: ConfirmationRequiredException): ProblemDetail {
        log.info("Confirmation required: {}", ex.message)
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.message)
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(ex: Exception): ProblemDetail {
        log.error("Unexpected error", ex)
        return ProblemDetail.forStatusAndDetail(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "An unexpected error occurred",
        )
    }
}
