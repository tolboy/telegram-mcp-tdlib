package dev.telegrammcp.server.auth

import java.time.Instant

/**
 * Type-safe representation of TDLib authorization states relevant to interactive auth.
 *
 * Each subclass carries only the payload the wizard UI actually needs.
 */
sealed class AuthState(val name: String) {

    abstract val timestamp: Instant

    /** No auth attempt has been initiated yet. */
    data class Idle(
        override val timestamp: Instant = Instant.now(),
    ) : AuthState("idle")

    /** Waiting for the user to submit API credentials / phone number. */
    data class WaitingPhoneNumber(
        override val timestamp: Instant = Instant.now(),
    ) : AuthState("waitingPhoneNumber")

    /**
     * TDLib sent a login code; the wizard should prompt for it.
     *
     * [codeChannel] says *where* Telegram put it (see [AuthCodeChannel]) —
     * without it the wizard cannot tell the user whether to look in the
     * Telegram app, in SMS, or at an incoming call.
     */
    data class WaitingCode(
        val phoneNumber: String,
        val codeChannel: String? = null,
        val nextCodeChannel: String? = null,
        val resendTimeoutSeconds: Int? = null,
        override val timestamp: Instant = Instant.now(),
    ) : AuthState("waitingCode")

    /** QR-code login flow — the wizard should render the link as a QR. */
    data class WaitingQr(
        val qrLink: String,
        override val timestamp: Instant = Instant.now(),
    ) : AuthState("waitingQr")

    /** 2FA password required. */
    data class WaitingPassword(
        val passwordHint: String = "",
        override val timestamp: Instant = Instant.now(),
    ) : AuthState("waitingPassword")

    /** TDLib signed in; the client and any expected-account verdict are still pending. */
    data class VerifyingAccount(
        override val timestamp: Instant = Instant.now(),
    ) : AuthState("verifyingAccount")

    /** Fully authenticated — MCP tools are operational. */
    data class Ready(
        override val timestamp: Instant = Instant.now(),
    ) : AuthState("ready")

    /**
     * Session was closed by an explicit user-initiated logout (`POST /auth/logout`).
     * Distinct from [Error] so the wizard can show a neutral "disconnected" screen
     * instead of a red error banner. Transitions to [Idle] on the next [initAuth].
     */
    data class LoggedOut(
        override val timestamp: Instant = Instant.now(),
    ) : AuthState("loggedOut")

    /** Terminal error state — credentials or session are invalid. */
    data class Error(
        val errorMessage: String,
        override val timestamp: Instant = Instant.now(),
    ) : AuthState("error")
}
