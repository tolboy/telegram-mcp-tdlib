package dev.telegrammcp.server.client

import it.tdlight.ClientFactory
import it.tdlight.client.SimpleTelegramClientFactory
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Serializes the shared native receiver's final close and the next factory acquisition.
 * TDLight clears its common-factory slot before the old receiver has stopped, so
 * separate account/auth owners must not acquire a replacement during that drain.
 */
internal object TdLibFactoryLifecycle {
    private val gate = TdLibFactoryLifecycleGate()

    fun create(): SimpleTelegramClientFactory = gate.acquire { SimpleTelegramClientFactory() }

    fun close(factory: SimpleTelegramClientFactory) = gate.closeFactory { factory.close() }

    /** Offline native checks use the same gate without starting automatic authorization. */
    fun acquireCommon(): ClientFactory = gate.acquire { ClientFactory.acquireCommonClientFactory() }

    fun close(factory: ClientFactory) = gate.closeFactory { factory.close() }
}

/** Owns a worker whose blocking native close is never interrupted by its caller. */
internal class TdLibFactoryLifecycleGate : AutoCloseable {
    @Volatile private var worker: Thread? = null
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "tdlib-native-factory-lifecycle").apply {
            isDaemon = true
            worker = this
        }
    }
    // Only the lifecycle worker accesses these fields, including re-entrant calls.
    private var closingDepth = 0
    private var closeFailure: Throwable? = null

    fun <T> acquire(factory: () -> T): T = onWorker {
        check(closingDepth == 0) { "A native factory cannot be acquired from inside its close operation" }
        closeFailure?.let {
            throw IllegalStateException("TDLib factory closure failed; restart the server before acquiring another native factory", it)
        }
        factory()
    }

    /** Existing owners may still close after failure, but no new receiver may start. */
    fun closeFactory(close: () -> Unit) = onWorker {
        closingDepth++
        val previouslyInterrupted = Thread.interrupted()
        try {
            close()
            // Upstream catches InterruptedException, restores the flag, and
            // returns before its receiver has actually stopped. Fail closed.
            check(!Thread.currentThread().isInterrupted) { "Native factory closure returned with an interrupted lifecycle worker" }
        } catch (failure: Throwable) {
            if (closeFailure == null) closeFailure = failure
            throw failure
        } finally {
            closingDepth--
            if (previouslyInterrupted) Thread.currentThread().interrupt()
        }
    }

    private fun <T> onWorker(action: () -> T): T {
        // Native lifecycle callbacks/tests may re-enter this helper. Do not
        // submit to the same single worker and wait for ourselves.
        if (Thread.currentThread() === worker) return action()
        return awaitCompletion(executor.submit(Callable { action() }))
    }

    private fun <T> awaitCompletion(result: Future<T>): T {
        var interrupted = false
        try {
            while (true) {
                try {
                    return result.get()
                } catch (_: InterruptedException) {
                    // Cancelling an auth caller must not let the next factory
                    // overtake a native close that is still draining.
                    interrupted = true
                } catch (failed: ExecutionException) {
                    throw failed.cause ?: failed
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    /** Test-instance cleanup only; the application singleton lives until JVM exit. */
    override fun close() {
        // No shutdownNow: interrupting this worker defeats the native-close invariant.
        executor.shutdown()
    }
}
