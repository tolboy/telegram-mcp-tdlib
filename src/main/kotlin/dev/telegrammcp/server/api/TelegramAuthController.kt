package dev.telegrammcp.server.api

import dev.telegrammcp.server.auth.AuthStateDto
import dev.telegrammcp.server.auth.AuthWizardProperties
import dev.telegrammcp.server.auth.AuthState
import dev.telegrammcp.server.auth.CredentialsRequest
import dev.telegrammcp.server.auth.SubmitCodeRequest
import dev.telegrammcp.server.auth.SubmitPasswordRequest
import dev.telegrammcp.server.auth.TelegramAuthOrchestrator
import dev.telegrammcp.server.auth.TelegramAuthStateHolder
import dev.telegrammcp.server.client.TelegramAccountRegistry
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * HTTP API for interactive Telegram authentication.
 *
 * These endpoints support a local setup wizard and are exempted from API-key
 * authentication only for localhost/Docker-internal callers.
 */
@RestController
@RequestMapping("/auth")
class TelegramAuthController(
    private val orchestrator: TelegramAuthOrchestrator,
    private val authStateHolder: TelegramAuthStateHolder,
    private val accountRegistry: TelegramAccountRegistry,
    private val authWizard: AuthWizardProperties,
) {

    companion object {
        private const val MAX_API_HASH_LENGTH = 128
        private const val MAX_PHONE_NUMBER_LENGTH = 20
        private const val MAX_AUTH_CODE_LENGTH = 20
        private const val MAX_PASSWORD_LENGTH = 256
    }

    /**
     * Submit API credentials and optionally a phone number to start auth.
     *
     * If a valid TDLib session already exists on disk, this will transition
     * straight to `ready` without requiring code/QR input.
     */
    @PostMapping("/credentials")
    fun submitCredentials(@RequestBody request: CredentialsRequest): ResponseEntity<AuthStateDto> {
        multiAccountConflict()?.let { return it }
        if (request.apiId <= 0 || request.apiHash.isBlank() || request.apiHash.length > MAX_API_HASH_LENGTH) {
            return ResponseEntity.badRequest().body(
                AuthStateDto(
                    state = "error",
                    errorMessage = "apiId must be positive and apiHash must be non-blank (max $MAX_API_HASH_LENGTH chars)",
                    timestamp = java.time.Instant.now(),
                ),
            )
        }

        if (request.phoneNumber != null && request.phoneNumber.length > MAX_PHONE_NUMBER_LENGTH) {
            return ResponseEntity.badRequest().body(
                AuthStateDto(
                    state = "error",
                    errorMessage = "phoneNumber must be at most $MAX_PHONE_NUMBER_LENGTH chars",
                    timestamp = java.time.Instant.now(),
                ),
            )
        }

        orchestrator.initAuth(request.apiId, request.apiHash, request.phoneNumber)

        return ResponseEntity.accepted().body(AuthStateDto.from(authStateHolder.getState()))
    }

    /**
     * Returns the current auth state snapshot.
     * The wizard should poll this endpoint to drive its staged UI.
     */
    @GetMapping("/state")
    fun getState(): AuthStateDto = AuthStateDto.from(authStateHolder.getState())

    /**
     * Request QR-code authentication instead of SMS.
     *
     * Only valid when TDLib is waiting for the phone number (fresh client
     * created via `/auth/credentials` with empty phone). Once the phone has
     * been submitted, TDLib rejects `RequestQrCodeAuthentication` as
     * "unexpected" — in that case the caller should `/auth/logout` + re-submit
     * credentials without a phone instead.
     */
    @PostMapping("/request-qr")
    fun requestQr(): ResponseEntity<AuthStateDto> {
        multiAccountConflict()?.let { return it }
        val current = authStateHolder.getState()
        val qrAllowed = current is AuthState.Idle ||
            current is AuthState.WaitingPhoneNumber ||
            current is AuthState.WaitingQr
        if (!qrAllowed) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(
                AuthStateDto(
                    state = current.name,
                    errorMessage = "QR auth not available in state ${current.name}. " +
                        "Call /auth/logout and re-submit /auth/credentials with empty phone.",
                    timestamp = java.time.Instant.now(),
                ),
            )
        }
        return try {
            orchestrator.requestQr()
            ResponseEntity.ok(AuthStateDto.from(authStateHolder.getState()))
        } catch (e: IllegalStateException) {
            ResponseEntity.status(HttpStatus.CONFLICT).body(
                AuthStateDto(
                    state = current.name,
                    errorMessage = e.message,
                    timestamp = java.time.Instant.now(),
                ),
            )
        }
    }

    /**
     * Submit the login code received via SMS/call.
     */
    @PostMapping("/submit-code")
    fun submitCode(@RequestBody request: SubmitCodeRequest): ResponseEntity<AuthStateDto> {
        multiAccountConflict()?.let { return it }
        if (request.code.isBlank() || request.code.length > MAX_AUTH_CODE_LENGTH) {
            return ResponseEntity.badRequest().body(
                AuthStateDto(
                    state = "error",
                    errorMessage = "code is required (max $MAX_AUTH_CODE_LENGTH chars)",
                    timestamp = java.time.Instant.now(),
                ),
            )
        }

        val accepted = authStateHolder.submitCode(request.code)
        return if (accepted) {
            ResponseEntity.ok(AuthStateDto.from(authStateHolder.getState()))
        } else {
            ResponseEntity.status(HttpStatus.CONFLICT).body(
                AuthStateDto(
                    state = authStateHolder.getState().name,
                    errorMessage = "No pending code request — current state: ${authStateHolder.getState().name}",
                    timestamp = java.time.Instant.now(),
                ),
            )
        }
    }

    /**
     * Submit the 2FA password.
     */
    @PostMapping("/submit-password")
    fun submitPassword(@RequestBody request: SubmitPasswordRequest): ResponseEntity<AuthStateDto> {
        multiAccountConflict()?.let { return it }
        if (request.password.isBlank() || request.password.length > MAX_PASSWORD_LENGTH) {
            return ResponseEntity.badRequest().body(
                AuthStateDto(
                    state = "error",
                    errorMessage = "password is required (max $MAX_PASSWORD_LENGTH chars)",
                    timestamp = java.time.Instant.now(),
                ),
            )
        }

        val accepted = authStateHolder.submitPassword(request.password)
        return if (accepted) {
            ResponseEntity.ok(AuthStateDto.from(authStateHolder.getState()))
        } else {
            ResponseEntity.status(HttpStatus.CONFLICT).body(
                AuthStateDto(
                    state = authStateHolder.getState().name,
                    errorMessage = "No pending password request — current state: ${authStateHolder.getState().name}",
                    timestamp = java.time.Instant.now(),
                ),
            )
        }
    }

    /**
     * Log out from the current TDLib session.
     */
    @PostMapping("/logout")
    fun logout(): ResponseEntity<AuthStateDto> {
        multiAccountConflict()?.let { return it }
        orchestrator.logout()
        return ResponseEntity.ok(AuthStateDto.from(authStateHolder.getState()))
    }

    private fun multiAccountConflict(): ResponseEntity<AuthStateDto>? {
        if (authWizard.enabled) return null
        if (accountRegistry.labels() == listOf("default")) return null
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
            AuthStateDto(
                state = "named_accounts_configured",
                errorMessage = "Interactive /auth endpoints are disabled when named accounts are configured. " +
                    "Configure each account's TDLib session and secrets before startup.",
                timestamp = java.time.Instant.now(),
            ),
        )
    }
}
