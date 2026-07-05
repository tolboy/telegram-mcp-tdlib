package dev.telegrammcp.server.client

import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicReference

/**
 * Thread-safe holder that exposes a single [TelegramClientService] proxy.
 *
 * The proxy delegates every call to whatever concrete implementation is currently
 * set via [swap]. This lets the interactive auth orchestrator hot-swap the NoOp
 * fallback for a real TDLib-backed service after successful login — without
 * touching any of the 30+ tool classes that inject [TelegramClientService].
 *
 * Uses a JDK dynamic proxy so it's zero-maintenance when new methods are added
 * to the interface.
 */
class DelegatingTelegramClientService private constructor(
    private val delegateRef: AtomicReference<TelegramClientService>,
) {

    /** The proxy instance that should be registered as the Spring bean. */
    val proxy: TelegramClientService = Proxy.newProxyInstance(
        TelegramClientService::class.java.classLoader,
        arrayOf(TelegramClientService::class.java),
        DelegatingHandler(delegateRef),
    ) as TelegramClientService

    /** Hot-swaps the underlying implementation. Takes effect on the next call. */
    fun swap(newDelegate: TelegramClientService) {
        delegateRef.set(newDelegate)
    }

    /** Returns the current concrete delegate (for diagnostics / tests). */
    fun current(): TelegramClientService = delegateRef.get()

    companion object {
        fun wrapping(initial: TelegramClientService): DelegatingTelegramClientService =
            DelegatingTelegramClientService(AtomicReference(initial))
    }

    private class DelegatingHandler(
        private val ref: AtomicReference<TelegramClientService>,
    ) : InvocationHandler {

        override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any? {
            val target = ref.get()
            return try {
                if (args != null) method.invoke(target, *args) else method.invoke(target)
            } catch (e: InvocationTargetException) {
                // Unwrap so callers see the real exception, not a reflection wrapper.
                // Use `cause` (idiomatic Kotlin chain) with `e` as a defensive fallback
                // for the theoretical case of InvocationTargetException(null).
                throw e.cause ?: e
            } catch (e: IllegalAccessException) {
                throw IllegalStateException("Unable to invoke method ${method.name} via proxy", e)
            } catch (e: IllegalArgumentException) {
                throw IllegalStateException("Invalid proxy invocation for method ${method.name}", e)
            }
        }
    }
}
