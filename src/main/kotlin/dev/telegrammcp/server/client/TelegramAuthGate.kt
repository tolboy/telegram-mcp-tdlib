package dev.telegrammcp.server.client

import dev.telegrammcp.server.exception.TdLibAuthException
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Moves the wait for TDLib authentication off the startup path.
 *
 * `SimpleTelegramClientBuilder.build` returns a usable client immediately and
 * only authentication is asynchronous, so blocking the bean that creates it
 * also blocks the MCP `initialize` response. A stdio client drops the handshake
 * after 60 s, marks the server failed, and the connector disappears. The gate
 * lets startup finish at once and moves the wait to the first tool call, where
 * a failure is reportable as a plain tool error.
 *
 * Uses a JDK dynamic proxy, like [DelegatingTelegramClientService], so the gate
 * stays zero-maintenance as [TelegramClientService] grows.
 */
class TelegramAuthGate(
    private val label: String,
    private val readyTimeout: Duration,
) {
    private val terminal = CountDownLatch(1)
    private val state = AtomicReference<AuthState>(AuthState.Pending)
    private val transitionLock = Any()

    /** TDLib reached `AuthorizationStateReady`; calls pass through while the session remains ready. */
    fun markReady() {
        markReady {}
    }

    /** Runs nonblocking activation before opening the gate, only if the session has not failed. */
    internal fun markReady(activate: () -> Unit): Boolean = synchronized(transitionLock) {
        val current = state.get()
        if (current !is AuthState.Pending && current !is AuthState.Verifying) return@synchronized false
        activate()
        state.set(AuthState.Ready)
        terminal.countDown()
        true
    }

    /**
     * TDLib is signed in and the expected-account check is running until
     * [deadlineNanos] ([System.nanoTime] based). Calls still wait, and a waiting
     * call may wait past its own ready-timeout up to that shared deadline
     * (plus [VERDICT_GRACE]), never past its tool-call deadline, so it gets the
     * check's verdict. If its tool-call deadline comes first it fails with a
     * clear "still verifying" error; nothing is ever passed through unverified.
     */
    fun markVerifying(deadlineNanos: Long) {
        state.compareAndSet(AuthState.Pending, AuthState.Verifying(deadlineNanos))
    }

    /**
     * Records the first authentication/session failure. Failure is absorbing:
     * READY cannot erase an earlier startup failure, and an unexpected close
     * after READY makes later tool calls fail fast instead of reaching a closed
     * TDLib client.
     */
    fun markFailed(reason: String) {
        synchronized(transitionLock) {
            if (state.get() is AuthState.Failed) return
            state.set(AuthState.Failed(reason))
            terminal.countDown()
        }
    }

    /** True only while the TDLib session is currently ready. */
    fun isReady(): Boolean = state.get() === AuthState.Ready

    /**
     * Waits until the account is authenticated.
     *
     * @throws TdLibAuthException when authentication failed, or is still not
     * complete after the configured timeout. The message is the one shown to
     * the MCP client, so it names the account and the way out.
     */
    fun awaitReady() {
        when (val current = state.get()) {
            AuthState.Ready -> return
            is AuthState.Failed -> throw authError(current.reason)
            AuthState.Pending, is AuthState.Verifying -> Unit
        }

        // The wait counts toward the tool call's deadline and never outlasts it.
        // MCP cancellation ends this call only. Login itself keeps running.
        val startedAt = System.nanoTime()
        val callDeadline = ToolCallDeadline.remainingNanos()?.let { startedAt + it.coerceAtLeast(0) }
        val ownDeadline = startedAt + readyTimeout.toNanos()
        fun deadline(): Long {
            // While the account check runs, wait for its verdict: the shared deadline wins over our own timer.
            val verifying = state.get() as? AuthState.Verifying
            val wanted = if (verifying == null) ownDeadline else maxOf(ownDeadline, verifying.deadlineNanos + VERDICT_GRACE.toNanos())
            return if (callDeadline == null) wanted else minOf(wanted, callDeadline)
        }
        try {
            while (state.get().let { it === AuthState.Pending || it is AuthState.Verifying }) {
                McpCallCancellation.throwIfLocalWait("authentication")
                val left = deadline() - System.nanoTime()
                if (left <= 0) break
                if (terminal.await(minOf(left, 50_000_000L), TimeUnit.NANOSECONDS)) break
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw authError("Interrupted while waiting for Telegram account '$label' to authenticate")
        }

        val waitedSeconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startedAt)
        when (val finalState = state.get()) {
            AuthState.Ready -> return
            is AuthState.Failed -> throw authError(finalState.reason)
            is AuthState.Verifying -> throw authError(
                "Telegram account '$label' is signed in, but the expected-account check has not finished " +
                    "within this tool call (waited ${waitedSeconds}s). Nothing was sent; retry shortly.",
            )
            AuthState.Pending -> {
                throw authError(
                    "Telegram account '$label' is not authenticated yet (waited ${waitedSeconds}s). " +
                        "Complete the login with 'telegram-mcp auth', or supply TDLIB_AUTH_CODE / " +
                        "TDLIB_2FA_PASSWORD for a headless start.",
                )
            }
        }
    }

    /** Wraps [service] so every Telegram operation waits for a ready session. */
    fun gate(service: TelegramClientService): TelegramClientService = Proxy.newProxyInstance(
        TelegramClientService::class.java.classLoader,
        arrayOf(TelegramClientService::class.java),
        AuthGateInvocationHandler(this, service),
    ) as TelegramClientService

    private fun authError(reason: String) = TdLibAuthException(reason)

    companion object {
        /** Extra time a waiter allows past the account check's deadline for its callbacks to land. */
        val VERDICT_GRACE: Duration = Duration.ofMillis(500)
    }

    private sealed interface AuthState {
        data object Pending : AuthState
        data class Verifying(val deadlineNanos: Long) : AuthState
        data object Ready : AuthState
        data class Failed(val reason: String) : AuthState
    }

    private class AuthGateInvocationHandler(
        private val gate: TelegramAuthGate,
        private val target: TelegramClientService,
    ) : InvocationHandler {

        override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any? {
            if (method.declaringClass == Any::class.java) {
                return when (method.name) {
                    "toString" -> "AuthGatedTelegramClientService(${gate.label})"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> null
                }
            }

            gate.awaitReady()
            return try {
                if (args == null) method.invoke(target) else method.invoke(target, *args)
            } catch (e: InvocationTargetException) {
                throw e.cause ?: e
            } catch (e: ReflectiveOperationException) {
                throw IllegalStateException("Unable to invoke Telegram operation ${method.name}", e)
            }
        }
    }
}
