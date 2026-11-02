package dev.telegrammcp.server.runtime

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.Banner
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.annotation.Configuration
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A supervisor may stop the server while Spring is still starting: `docker
 * stop` on a slow host, an MCP client that gives up, the release smoke. The
 * signal starts JVM shutdown before Spring has registered its own hooks, and a
 * hook registered after that point fails with "Shutdown in progress", turning
 * an orderly stop into an "Application run failed" stack trace.
 *
 * The race is made deterministic by starting JVM shutdown first and only then
 * running Spring, in a child JVM because shutdown cannot be undone.
 */
class LateSignalStartupTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `server application starts and stops cleanly when the JVM is already shutting down`() {
        val result = runProbe(LateSignalStartupProbe.SERVER_BUILDER)

        assertTrue(result.stdout.contains("${LateSignalStartupProbe.MARKER} shutdown-in-progress"), result.describe())
        assertTrue(result.stdout.contains("${LateSignalStartupProbe.MARKER} started"), result.describe())
        assertFalse(result.combined.contains("Shutdown in progress"), result.describe())
        assertFalse(result.combined.contains("Application run failed"), result.describe())
        assertTrue(result.stderr.contains("${ServerShutdown.FINAL_STDERR_PREFIX} exit_code=0"), result.describe())
        assertEquals(0, result.exitCode, result.describe())
    }

    /**
     * Control: Spring's default hook registration loses the same race, so the
     * test above is not passing because the probe failed to reproduce it.
     */
    @Test
    fun `default Spring hook registration fails when the JVM is already shutting down`() {
        val result = runProbe(LateSignalStartupProbe.DEFAULT_BUILDER)

        assertTrue(result.stdout.contains("${LateSignalStartupProbe.MARKER} shutdown-in-progress"), result.describe())
        assertTrue(result.stdout.contains("${LateSignalStartupProbe.MARKER} failed"), result.describe())
        assertTrue(result.stdout.contains("Shutdown in progress"), result.describe())
    }

    private fun runProbe(mode: String): ProbeResult {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        // An argument file keeps the long test classpath off the command line,
        // which Windows limits to 32K characters.
        val argFile = tempDir.resolve("probe-$mode.args")
        val classPath = System.getProperty("java.class.path")
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        Files.writeString(argFile, "-cp \"$classPath\"\n")
        val stdout = tempDir.resolve("probe-$mode.out")
        val stderr = tempDir.resolve("probe-$mode.err")
        val process = ProcessBuilder(
            java,
            "--enable-preview",
            "--enable-native-access=ALL-UNNAMED",
            "@$argFile",
            LateSignalStartupProbe::class.java.name,
            mode,
        )
            .redirectInput(ProcessBuilder.Redirect.PIPE)
            .redirectOutput(stdout.toFile())
            .redirectError(stderr.toFile())
            .start()
        process.outputStream.close()
        if (!process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
            error("Probe '$mode' did not exit within ${PROBE_TIMEOUT_SECONDS}s:\n${Files.readString(stdout)}\n${Files.readString(stderr)}")
        }
        return ProbeResult(process.exitValue(), Files.readString(stdout), Files.readString(stderr))
    }

    private data class ProbeResult(val exitCode: Int, val stdout: String, val stderr: String) {
        val combined: String get() = stdout + stderr

        fun describe(): String = "exit=$exitCode\n--- stdout ---\n$stdout\n--- stderr ---\n$stderr"
    }

    private companion object {
        const val PROBE_TIMEOUT_SECONDS = 60L
    }
}

/** Child-JVM entry point for [LateSignalStartupTest]. */
object LateSignalStartupProbe {
    const val MARKER = "LATE_SIGNAL_PROBE"
    const val SERVER_BUILDER = "server"
    const val DEFAULT_BUILDER = "spring-default"

    @Configuration(proxyBeanMethods = false)
    open class ProbeConfiguration

    @JvmStatic
    fun main(args: Array<String>) {
        val builder = when (args.single()) {
            SERVER_BUILDER -> serverApplicationBuilder(ProbeConfiguration::class.java)
            DEFAULT_BUILDER -> SpringApplicationBuilder(ProbeConfiguration::class.java)
            else -> error("unknown probe mode ${args.single()}")
        }
        // Same order as the real entrypoints: the signal hook first, then Spring.
        val shutdown = ServerShutdown(
            graceMillis = 4_000,
            halt = { code -> Runtime.getRuntime().halt(code) },
        )
        checkNotNull(installSignalShutdownHook(shutdown)) { "signal hook was not registered" }

        // A termination signal reaches the JVM as Runtime.exit on a signal
        // thread; exiting from another thread starts the same sequence and runs
        // the signal hook while this thread carries on starting Spring.
        Thread({ exitProcess(0) }, "probe-signal").apply { isDaemon = true }.start()
        awaitShutdownInProgress()
        report("shutdown-in-progress")

        try {
            val context = builder
                .web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF)
                .logStartupInfo(false)
                .run()
            report("started")
            shutdown.attach(context)
        } catch (failure: Throwable) {
            val messages = generateSequence(failure) { it.cause }.map { "${it.javaClass.name}: ${it.message}" }
            report("failed ${messages.joinToString(" <- ")}")
        }
        // Returning lets the JVM finish its shutdown: the signal hook closes
        // the context (or gives up waiting for it) and halts.
    }

    private fun awaitShutdownInProgress() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < deadline) {
            val probe = Thread {}
            try {
                Runtime.getRuntime().addShutdownHook(probe)
                Runtime.getRuntime().removeShutdownHook(probe)
            } catch (_: IllegalStateException) {
                return
            }
            Thread.sleep(10)
        }
        error("JVM shutdown did not start")
    }

    private fun report(event: String) {
        println("$MARKER $event")
        System.out.flush()
    }
}
