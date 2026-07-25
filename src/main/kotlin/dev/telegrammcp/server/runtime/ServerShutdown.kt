package dev.telegrammcp.server.runtime

import dev.telegrammcp.server.util.StructuredLogger
import org.springframework.context.ConfigurableApplicationContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * The single exit path of the server process.
 *
 * Closing the Spring context is not enough to end the JVM: TDLib keeps its own
 * non-daemon threads (`ResponseReceiver`) alive for the whole process lifetime.
 * Under `docker run -i` that leaves an orphaned container behind after the MCP
 * client is gone — one that still holds `td.binlog`, so the next start cannot
 * lock the session. Every termination therefore runs the graceful close under a
 * hard deadline and then halts the JVM.
 */
class ServerShutdown internal constructor(
    private val graceMillis: Long,
    private val halt: (Int) -> Unit,
) {
    private val log = StructuredLogger.forClass<ServerShutdown>()
    private val context = AtomicReference<ConfigurableApplicationContext?>()
    private val contextAttached = CountDownLatch(1)
    private val started = AtomicBoolean(false)
    private val gracefulCloseFinished = CountDownLatch(1)

    /**
     * Publishes the context that a shutdown should close.
     *
     * Shutdown can be requested before the context exists — stdin may reach EOF
     * while the application is still starting — so [requestShutdown] waits for
     * this hand-off inside its grace window instead of assuming an order.
     */
    fun attach(applicationContext: ConfigurableApplicationContext) {
        context.set(applicationContext)
        contextAttached.countDown()
    }

    /**
     * Ends the process. Idempotent: the first caller owns the exit and later
     * ones return without queueing a second shutdown.
     */
    fun requestShutdown(reason: String, exitCode: Int = 0) {
        if (!started.compareAndSet(false, true)) return
        log.info("Shutting down (exit code {}): {}", exitCode, reason)
        // Never run on the caller's thread: EOF is reported by the MCP transport
        // reader and TDLib failures by a TDLib update thread, and both must be
        // free to return immediately.
        thread(isDaemon = true, name = "server-shutdown") { closeGracefully(exitCode) }
        thread(isDaemon = true, name = "server-shutdown-watchdog") { haltAfterGrace(exitCode) }
    }

    private fun closeGracefully(exitCode: Int) {
        awaitQuietly(contextAttached, graceMillis / 2)
        context.get()?.let { applicationContext ->
            runCatching { applicationContext.close() }
                .onFailure { log.warn("Application context did not close cleanly: {}", it.message) }
        }
        gracefulCloseFinished.countDown()
        halt(exitCode)
    }

    private fun haltAfterGrace(exitCode: Int) {
        if (awaitQuietly(gracefulCloseFinished, graceMillis)) return
        log.warn("Graceful shutdown exceeded {} ms; halting the JVM", graceMillis)
        halt(exitCode)
    }

    private fun awaitQuietly(latch: CountDownLatch, timeoutMillis: Long): Boolean = try {
        latch.await(timeoutMillis, TimeUnit.MILLISECONDS)
    } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    companion object {
        /** Grace for the graceful close before the JVM is halted. */
        const val DEFAULT_GRACE_MILLIS = 5_000L

        /** Process-wide instance: one JVM has one stdin and one exit. */
        val INSTANCE: ServerShutdown = ServerShutdown(DEFAULT_GRACE_MILLIS) { code ->
            Runtime.getRuntime().halt(code)
        }
    }
}
