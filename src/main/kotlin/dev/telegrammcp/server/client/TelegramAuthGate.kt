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

    /** TDLib reached `AuthorizationStateReady`; calls pass through while the session remains ready. */
    fun markReady() {
        if (state.compareAndSet(AuthState.Pending, AuthState.Ready)) {
            terminal.countDown()
        }
    }

    /**
     * Records the first authentication/session failure. Failure is absorbing:
     * READY cannot erase an earlier startup failure, and an unexpected close
     * after READY makes later tool calls fail fast instead of reaching a closed
     * TDLib client.
     */
    fun markFailed(reason: String) {
        while (true) {
            val current = state.get()
            if (current is AuthState.Failed) return
            if (state.compareAndSet(current, AuthState.Failed(reason))) {
                terminal.countDown()
                return
            }
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
            AuthState.Pending -> Unit
        }

        try {
            terminal.await(readyTimeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw authError("Interrupted while waiting for Telegram account '$label' to authenticate")
        }

        when (val finalState = state.get()) {
            AuthState.Ready -> return
            is AuthState.Failed -> throw authError(finalState.reason)
            AuthState.Pending -> {
                throw authError(
                    "Telegram account '$label' is not authenticated yet (waited ${readyTimeout.toSeconds()}s). " +
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

    private sealed interface AuthState {
        data object Pending : AuthState
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
