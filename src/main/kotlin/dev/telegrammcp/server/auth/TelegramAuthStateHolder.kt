package dev.telegrammcp.server.auth

import dev.telegrammcp.server.util.StructuredLogger
import org.springframework.stereotype.Component
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference

/**
 * Thread-safe singleton holding the current TDLib authorization state.
 *
 * Acts as the communication bus between:
 * - [InteractiveClientInteraction] (producer — updates state when TDLib asks for input)
 * - [TelegramAuthController] (consumer — reads state; completes pending futures with user input)
 *
 * The [pendingCode] and [pendingPassword] futures are created by [InteractiveClientInteraction]
 * when TDLib requests a code or 2FA password. The HTTP controller completes them with values
 * submitted by the wizard UI.
 */
@Component
class TelegramAuthStateHolder {

    private val log = StructuredLogger.forClass<TelegramAuthStateHolder>()

    private val stateRef = AtomicReference<AuthState>(AuthState.Idle())

    /**
     * Future that [InteractiveClientInteraction] waits on for the login code.
     * Created fresh each time TDLib transitions to WAIT_CODE.
     */
    private val pendingCodeRef = AtomicReference<CompletableFuture<String>?>(null)

    /**
     * Future that [InteractiveClientInteraction] waits on for the 2FA password.
     * Created fresh each time TDLib transitions to WAIT_PASSWORD.
     */
    private val pendingPasswordRef = AtomicReference<CompletableFuture<String>?>(null)

    fun getState(): AuthState = stateRef.get()

    fun setState(state: AuthState) {
        val prev = stateRef.getAndSet(state)
        log.info("Auth state transition: {} → {}", prev.name, state.name)
    }

    /**
     * Creates a new [CompletableFuture] for the login code.
     * Called by [InteractiveClientInteraction] when TDLib enters WAIT_CODE.
     */
    fun awaitCode(): CompletableFuture<String> {
        val future = CompletableFuture<String>()
        pendingCodeRef.getAndSet(future)?.cancel(false)
        return future
    }

    /**
     * Creates a new [CompletableFuture] for the 2FA password.
     * Called by [InteractiveClientInteraction] when TDLib enters WAIT_PASSWORD.
     */
    fun awaitPassword(): CompletableFuture<String> {
        val future = CompletableFuture<String>()
        pendingPasswordRef.getAndSet(future)?.cancel(false)
        return future
    }

    /**
     * Completes the pending code future with the value from the wizard.
     * @return true if the code was accepted (a future was pending), false otherwise.
     */
    fun submitCode(code: String): Boolean {
        val future = pendingCodeRef.get() ?: return false
        val completed = future.complete(code)
        if (completed) {
            log.info("Login code submitted via interactive auth")
        }
        return completed
    }

    /**
     * Completes the pending password future with the value from the wizard.
     * @return true if the password was accepted (a future was pending), false otherwise.
     */
    fun submitPassword(password: String): Boolean {
        val future = pendingPasswordRef.get() ?: return false
        val completed = future.complete(password)
        if (completed) {
            log.info("2FA password submitted via interactive auth")
        }
        return completed
    }

    /** Resets state back to [AuthState.Idle] and cancels any pending futures. */
    fun reset() {
        pendingCodeRef.getAndSet(null)?.cancel(false)
        pendingPasswordRef.getAndSet(null)?.cancel(false)
        setState(AuthState.Idle())
    }
}
