package dev.telegrammcp.server.exception

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
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

    @ExceptionHandler(TelegramApiException::class)
    fun handleTelegramApi(ex: TelegramApiException): ProblemDetail {
        log.error("Telegram API failure: {}", ex.message, ex)
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, ex.message)
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
