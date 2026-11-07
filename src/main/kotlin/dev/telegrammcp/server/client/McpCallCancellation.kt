package dev.telegrammcp.server.client

import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

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
        boundKey.set(key)
    }

    fun unbind() {
        boundKey.remove()
    }

    fun <T> listen(block: () -> T): T {
        val key = boundKey.get() ?: return block()
        val flag = AtomicBoolean()
        signal.set(flag)
        synchronized(active) { active.getOrPut(key) { mutableListOf() }.add(flag) }
        return try {
            block()
        } finally {
            signal.remove()
            synchronized(active) {
                val flags = active[key] ?: return@synchronized
                flags.remove(flag)
                if (flags.isEmpty()) active.remove(key)
            }
        }
    }

    /** Wakes every local wait for [key]. Does not complete or cancel a TDLib future. */
    fun cancel(key: String) {
        val flags = synchronized(active) { active[key]?.toList().orEmpty() }
        flags.forEach { it.set(true) }
    }

    fun isSignalled(): Boolean = signal.get()?.get() == true

    /**
     * Waits for [future] without calling [CompletableFuture.cancel].
     * A signalled MCP cancellation throws [McpCallCancelledException].
     */
    fun <T> await(future: CompletableFuture<T>, timeoutNanos: Long): T {
        var remaining = timeoutNanos.coerceAtLeast(0)
        while (true) {
            if (isSignalled()) throw McpCallCancelledException()
            val slice = minOf(remaining, SLICE_NANOS)
            try {
                return future.get(slice, TimeUnit.NANOSECONDS)
            } catch (error: TimeoutException) {
                remaining -= slice
                if (remaining <= 0) throw error
            } catch (error: CancellationException) {
                throw error
            }
        }
    }
}

/** The MCP client asked to stop waiting. This is not a TDLib cancellation result. */
internal class McpCallCancelledException : RuntimeException("MCP client cancelled the local wait")
