package dev.telegrammcp.server.client

import dev.telegrammcp.server.exception.TelegramTimeoutException
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.asContextElement
import reactor.core.scheduler.Schedulers

/**
 * Local stop for one MCP JSON-RPC request.
 *
 * [cancel] only wakes callers blocked in [await] or [throwIfLocalWait]. It does
 * not cancel the TDLib future and it is not a report that Telegram aborted the
 * request. An HTTP disconnect is not a cancellation: nothing in this type
 * observes the socket.
 *
 * STDIO runs the tool on the SDK elastic scheduler. [installWorkerPropagation]
 * copies the flag onto that worker. The flag stays registered after the reader
 * thread detaches, so a later notification can still wake the tool.
 */
internal object McpCallCancellation {
    private const val SLICE_NANOS = 50_000_000L
    private const val HOOK = "telegram-mcp-cancellation"

    private val boundKey = ThreadLocal<String?>()
    private val signal = ThreadLocal<AtomicBoolean?>()
    private val active = ConcurrentHashMap<String, MutableList<AtomicBoolean>>()
    private val hookInstalled = AtomicBoolean()

    /** Associates the current thread with one session-scoped request id. */
    fun bind(key: String): AtomicBoolean {
        check(boundKey.get() == null) { "An MCP request is already bound to this thread" }
        val flag = AtomicBoolean()
        synchronized(active) { active.getOrPut(key) { mutableListOf() }.add(flag) }
        boundKey.set(key)
        signal.set(flag)
        return flag
    }

    fun unbind() {
        val key = boundKey.get()
        val flag = signal.get()
        if (key != null && flag != null) release(key, flag) else detachThread()
    }

    /** Drops the registration. Safe from a thread that no longer holds [flag]. */
    fun release(key: String, flag: AtomicBoolean) {
        synchronized(active) {
            active[key]?.let { flags ->
                flags.remove(flag)
                if (flags.isEmpty()) active.remove(key)
            }
        }
        if (signal.get() === flag) detachThread()
    }

    /** Clears this thread without forgetting the flag. The reader thread does this after scheduling the tool. */
    fun detachThread() {
        boundKey.remove()
        signal.remove()
    }

    /**
     * Copies a bound flag onto tasks scheduled from this thread.
     * A thread with no flag is unchanged. The hook is installed once.
     */
    fun installWorkerPropagation() {
        if (!hookInstalled.compareAndSet(false, true)) return
        Schedulers.onScheduleHook(HOOK) { runnable ->
            val key = boundKey.get()
            val flag = signal.get()
            if (key == null || flag == null) {
                runnable
            } else {
                Runnable {
                    val previousKey = boundKey.get()
                    val previousFlag = signal.get()
                    boundKey.set(key)
                    signal.set(flag)
                    try {
                        runnable.run()
                    } finally {
                        if (previousKey == null) detachThread() else {
                            boundKey.set(previousKey)
                            signal.set(previousFlag)
                        }
                    }
                }
            }
        }
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

    /**
     * Stops a local wait that has not handed work to TDLib.
     * Login, the change journal, and an in-flight approval stay as they are.
     */
    fun throwIfLocalWait(operation: String) {
        if (!isSignalled()) return
        throw TelegramTimeoutException(
            operation,
            TelegramTimeoutException.Reason.LOCAL_CANCEL,
            requestSent = false,
            retryable = !ToolCallDeadline.writeDispatched(),
            waitedMillis = 0,
            cause = McpCallCancelledException(),
        ).apply { healthFailure = false }
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
