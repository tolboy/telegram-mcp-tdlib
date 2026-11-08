package dev.telegrammcp.server.service

import java.util.concurrent.CompletableFuture
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Bounds outstanding SDK prompts independently of the tool call waiting for them.
 * Cancelling a local wait must not free a worker while its prompt is still in flight.
 */
internal class ElicitationExecutor(maxOutstanding: Int = 8) : AutoCloseable {
    private val executor = ThreadPoolExecutor(
        0, maxOutstanding, 15, TimeUnit.SECONDS, SynchronousQueue(),
        { task -> Thread(task, "approval-elicitation").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    /** No queue and no caller-runs: saturation refuses a prompt before sending it. */
    fun <T> submit(request: () -> T): CompletableFuture<T> {
        val result = CompletableFuture<T>()
        executor.execute {
            try {
                result.complete(request())
            } catch (failure: Throwable) {
                result.completeExceptionally(failure)
            }
        }
        return result
    }

    override fun close() {
        executor.shutdown()
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow()
        } catch (_: InterruptedException) {
            executor.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }
}
