package dev.telegrammcp.server.client

import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.asContextElement

/**
 * Local stop for one MCP JSON-RPC request.
 *
 * [cancel] only wakes callers blocked in [await]. It does not cancel the TDLib
 * future and it is not a report that Telegram aborted the request. An HTTP
 * disconnect is not a cancellation: nothing in this type observes the socket.
 */
internal object McpCallCancellation {
    private const val SLICE_NANOS = 50_000_000L

    private val boundKey = ThreadLocal<String?>()
    private val signal = ThreadLocal<AtomicBoolean?>()
    private val active = ConcurrentHashMap<String, MutableList<AtomicBoolean>>()

    /** Associates the current servlet thread with one session-scoped request id. */
    fun bind(key: String) {
        check(boundKey.get() == null) { "An MCP request is already bound to this thread" }
        val flag = AtomicBoolean()
        synchronized(active) { active.getOrPut(key) { mutableListOf() }.add(flag) }
        boundKey.set(key)
        signal.set(flag)
    }

    fun unbind() {
        val key = boundKey.get()
        val flag = signal.get()
        if (key != null && flag != null) synchronized(active) {
            active[key]?.let { flags ->
                flags.remove(flag)
                if (flags.isEmpty()) active.remove(key)
            }
        }
        boundKey.remove()
        signal.remove()
    }

    fun <T> listen(block: () -> T): T {
        // bind registers before progress/permissions/dispatch. Creating a new
        // signal here would lose a cancellation that arrived in that window.
        return block()
    }

    /** Wakes every local wait for [key]. Does not complete or cancel a TDLib future. */
    fun cancel(key: String) {
        val flags = synchronized(active) { active[key]?.toList().orEmpty() }
        flags.forEach { it.set(true) }
    }

    fun isSignalled(): Boolean = signal.get()?.get() == true

    fun throwIfSignalled() {
        if (isSignalled()) throw McpCallCancelledException()
    }

    /** Carries the same signal into bounded research worker coroutines. */
    fun coroutineContext(): kotlin.coroutines.CoroutineContext = signal.asContextElement()

    /**
     * Waits for [future] without calling [CompletableFuture.cancel].
     * A signalled MCP cancellation throws [McpCallCancelledException].
     */
    fun <T> await(future: CompletableFuture<T>, timeoutNanos: Long): T {
        val budget = timeoutNanos.coerceAtLeast(0)
        val started = System.nanoTime()
        while (true) {
            throwIfSignalled()
            val remaining = (budget - (System.nanoTime() - started)).coerceAtLeast(0)
            val slice = minOf(remaining, SLICE_NANOS)
            try {
                return future.get(slice, TimeUnit.NANOSECONDS)
            } catch (error: TimeoutException) {
                if (System.nanoTime() - started >= budget) throw error
            } catch (error: CancellationException) {
                throw error
            }
        }
    }
}

/** The MCP client asked to stop waiting. This is not a TDLib cancellation result. */
internal class McpCallCancelledException : CancellationException("MCP client cancelled the local wait")
