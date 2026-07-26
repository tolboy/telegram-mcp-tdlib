package dev.telegrammcp.server.runtime

import dev.telegrammcp.server.util.StructuredLogger
import org.springframework.context.ConfigurableApplicationContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
    private val stderr: (String) -> Unit = { line ->
        System.err.println(line)
        System.err.flush()
    },
) {
    private val log = StructuredLogger.forClass<ServerShutdown>()
    private val context = AtomicReference<ConfigurableApplicationContext?>()
    private val contextAttached = CountDownLatch(1)
    private val requestMonitor = Any()

    private var requestedShutdown: ShutdownRequest? = null
    private var haltStarted = false

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
     * Ends the process. The first caller starts the close and watchdog threads;
     * later callers never queue another shutdown. A fatal request can still
     * promote an in-flight clean exit so an EOF race does not hide the failure.
     */
    fun requestShutdown(reason: String, exitCode: Int = 0) {
        synchronized(requestMonitor) {
            if (haltStarted) return

            val activeRequest = requestedShutdown
            when {
                activeRequest == null -> {
                    requestedShutdown = ShutdownRequest(exitCode, reason)
                    log.info("Shutting down (exit code {}): {}", exitCode, reason)
                    // Never run on the caller's thread: EOF is reported by the MCP transport
                    // reader and TDLib failures by a TDLib update thread, and both must be
                    // free to return immediately.
                    thread(isDaemon = true, name = "server-shutdown") { closeGracefully() }
                    thread(isDaemon = true, name = "server-shutdown-watchdog") { haltAfterGrace() }
                }
                activeRequest.exitCode == 0 && exitCode != 0 -> {
                    requestedShutdown = ShutdownRequest(exitCode, reason)
                    log.warn("Escalating shutdown (exit code 0 -> {}): {}", exitCode, reason)
                }
            }
        }
    }

    private fun closeGracefully() {
        awaitQuietly(contextAttached, graceMillis / 2)
        context.get()?.let { applicationContext ->
            runCatching { applicationContext.close() }
                .onFailure { log.warn("Application context did not close cleanly: {}", it.message) }
        }
        gracefulCloseFinished.countDown()
        haltOnce()
    }

    private fun haltAfterGrace() {
        if (awaitQuietly(gracefulCloseFinished, graceMillis)) return
        log.warn("Graceful shutdown exceeded {} ms; halting the JVM", graceMillis)
        haltOnce()
    }

    /**
     * Freezes the final request, writes one machine-readable line to stderr,
     * and only then invokes the non-returning Runtime.halt path. Keeping this in
     * one method also prevents the graceful and watchdog threads from issuing
     * duplicate halts when a test halt implementation returns.
     */
    private fun haltOnce() {
        val finalRequest = synchronized(requestMonitor) {
            if (haltStarted) {
                null
            } else {
                haltStarted = true
                requestedShutdown ?: ShutdownRequest(exitCode = 0, reason = "unspecified")
            }
        } ?: return

        val oneLineReason = finalRequest.reason
            .replace('\r', ' ')
            .replace('\n', ' ')
            .trim()
            .ifBlank { "unspecified" }
        val finalLine = "$FINAL_STDERR_PREFIX exit_code=${finalRequest.exitCode} reason=$oneLineReason"
        runCatching { stderr(finalLine) }
            .onFailure { log.warn("Unable to write final shutdown line to stderr: {}", it.message) }
        halt(finalRequest.exitCode)
    }

    private fun awaitQuietly(latch: CountDownLatch, timeoutMillis: Long): Boolean = try {
        latch.await(timeoutMillis, TimeUnit.MILLISECONDS)
    } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    companion object {
        /** Stable prefix used by launchers and smoke tests to identify the final exit reason. */
        internal const val FINAL_STDERR_PREFIX = "TELEGRAM_MCP_SHUTDOWN"

        /** Grace for the graceful close before the JVM is halted. */
        const val DEFAULT_GRACE_MILLIS = 5_000L

        /** Process-wide instance: one JVM has one stdin and one exit. */
        val INSTANCE: ServerShutdown = ServerShutdown(
            graceMillis = DEFAULT_GRACE_MILLIS,
            halt = { code -> Runtime.getRuntime().halt(code) },
        )
    }

    private data class ShutdownRequest(
        val exitCode: Int,
        val reason: String,
    )
}
