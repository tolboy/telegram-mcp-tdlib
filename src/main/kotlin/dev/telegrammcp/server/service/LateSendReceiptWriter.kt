package dev.telegrammcp.server.service

import org.slf4j.LoggerFactory
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Keeps receipt fsync off TDLib's shared response thread, with a bounded backlog. */
internal class LateSendReceiptWriter(queueCapacity: Int = 256) : AutoCloseable {
    private val log = LoggerFactory.getLogger(LateSendReceiptWriter::class.java)
    private val executor = ThreadPoolExecutor(
        1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue(queueCapacity),
        { task -> Thread(task, "telegram-late-send-receipts").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    /** Never runs disk I/O on the submitting TDLib thread, even when saturated. */
    fun submit(write: () -> Unit): Boolean = try {
        executor.execute {
            try {
                write()
            } catch (error: Exception) {
                log.warn("Unable to persist a late Telegram delivery receipt; an unresolved send remains UNKNOWN ({})",
                    error.javaClass.simpleName)
            }
        }
        true
    } catch (_: RejectedExecutionException) {
        log.warn("Late Telegram receipt queue is full or closed; an unresolved send remains UNKNOWN")
        false
    }

    override fun close() {
        executor.shutdown()
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                val discarded = executor.shutdownNow().size
                log.warn("Late Telegram receipt writer did not drain; discarded {} queued receipt(s)", discarded)
            }
        } catch (_: InterruptedException) {
            executor.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }
}
