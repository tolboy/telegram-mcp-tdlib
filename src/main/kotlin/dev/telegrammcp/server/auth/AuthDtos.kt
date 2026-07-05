package dev.telegrammcp.server.auth

import java.time.Instant

// ── Request DTOs ────────────────────────────────────────────────────────────

data class CredentialsRequest(
    val apiId: Int,
    val apiHash: String,
    val phoneNumber: String? = null,
)

data class SubmitCodeRequest(
    val code: String,
)

data class SubmitPasswordRequest(
    val password: String,
)

// ── Response DTO ────────────────────────────────────────────────────────────

data class AuthStateDto(
    val state: String,
    val phoneNumber: String? = null,
    val qrLink: String? = null,
    val passwordHint: String? = null,
    val errorMessage: String? = null,
    val timestamp: Instant,
) {
    companion object {
        fun from(authState: AuthState): AuthStateDto = when (authState) {
            is AuthState.Idle -> AuthStateDto(
                state = authState.name,
                timestamp = authState.timestamp,
            )
            is AuthState.WaitingPhoneNumber -> AuthStateDto(
                state = authState.name,
                timestamp = authState.timestamp,
            )
            is AuthState.WaitingCode -> AuthStateDto(
                state = authState.name,
                phoneNumber = authState.phoneNumber,
                timestamp = authState.timestamp,
            )
            is AuthState.WaitingQr -> AuthStateDto(
                state = authState.name,
                qrLink = authState.qrLink,
                timestamp = authState.timestamp,
            )
            is AuthState.WaitingPassword -> AuthStateDto(
                state = authState.name,
                passwordHint = authState.passwordHint,
                timestamp = authState.timestamp,
            )
            is AuthState.Ready -> AuthStateDto(
                state = authState.name,
                timestamp = authState.timestamp,
            )
            is AuthState.LoggedOut -> AuthStateDto(
                state = authState.name,
                timestamp = authState.timestamp,
            )
            is AuthState.Error -> AuthStateDto(
                state = authState.name,
                errorMessage = authState.errorMessage,
                timestamp = authState.timestamp,
            )
        }
    }
}
