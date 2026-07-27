package dev.telegrammcp.server.runtime

import org.junit.jupiter.api.Test
import org.springframework.context.ConfigurableApplicationContext
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A container or service manager stops this process with a signal, so the
 * signal path has to reach the same bounded exit as stdin EOF. Without it the
 * JVM sits inside Spring's deadline-free shutdown hook while TDLib closes, and
 * the supervisor escalates to SIGKILL.
 */
class SignalShutdownHookTest {

    /**
     * Runs [body] with a hook installed on [shutdown], then fires the hook the
     * way the JVM would and removes it. The real hook is never left registered:
     * it would halt the test JVM when the suite ends.
     */
    private fun withInstalledHook(shutdown: ServerShutdown, body: (Thread) -> Unit) {
        val hook = requireNotNull(installSignalShutdownHook(shutdown)) {
            "the test JVM is not shutting down, so a hook must have been registered"
        }
        try {
            body(hook)
        } finally {
            Runtime.getRuntime().removeShutdownHook(hook)
        }
    }

    @Test
    fun `a termination signal closes the context and halts`() {
        val halted = CountDownLatch(1)
        val codes = mutableListOf<Int>()
        val context = mockk<ConfigurableApplicationContext>(relaxed = true)
        val shutdown = ServerShutdown(
            graceMillis = 1_000,
            halt = { code ->
                synchronized(codes) { codes += code }
                halted.countDown()
            },
            stderr = {},
        )
        shutdown.attach(context)

        withInstalledHook(shutdown) { hook ->
            hook.start()
            hook.join(5_000)
        }

        assertTrue(halted.await(5, TimeUnit.SECONDS), "a signal must end in a halt")
        assertEquals(0, synchronized(codes) { codes.first() }, "a signal is a clean exit")
        io.mockk.verify { context.close() }
    }

    /**
     * Startup loads TDLib's native libraries, so it is slow enough for a signal
     * to land before the hook is registered. The JVM then refuses the hook, and
     * shipping 1.11.0 showed what that costs: an IllegalStateException out of
     * main, Spring's unbounded hook left to close TDLib alone, and the
     * supervisor escalating to SIGKILL after its full grace period.
     */
    @Test
    fun `a signal during startup still gets a bounded shutdown`() {
        val halted = CountDownLatch(1)
        val context = mockk<ConfigurableApplicationContext>(relaxed = true)
        val shutdown = ServerShutdown(
            graceMillis = 500,
            halt = { halted.countDown() },
            stderr = {},
        )
        shutdown.attach(context)

        val hook = installSignalShutdownHook(shutdown) {
            throw IllegalStateException("Shutdown in progress")
        }

        assertNull(hook, "no hook can be registered once the JVM is shutting down")
        assertTrue(halted.await(5, TimeUnit.SECONDS), "the deadline must still apply")
        io.mockk.verify { context.close() }
    }

    /**
     * The blocking close is the whole reason the signal path needs a deadline:
     * TDLib's `@PreDestroy` can hang, and Spring's own hook would wait forever.
     */
    @Test
    fun `a context that never closes still halts within the grace window`() {
        val halted = CountDownLatch(1)
        val closeEntered = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val context = mockk<ConfigurableApplicationContext>(relaxed = true)
        every { context.close() } answers {
            closeEntered.countDown()
            releaseClose.await(10, TimeUnit.SECONDS)
        }
        val shutdown = ServerShutdown(
            graceMillis = 500,
            halt = { halted.countDown() },
            stderr = {},
        )
        shutdown.attach(context)

        try {
            withInstalledHook(shutdown) { hook ->
                hook.start()
                hook.join(5_000)
                assertTrue(closeEntered.await(5, TimeUnit.SECONDS), "the close must have been attempted")
                assertTrue(halted.await(5, TimeUnit.SECONDS), "a stuck close must not block the exit")
            }
        } finally {
            releaseClose.countDown()
        }
    }
}
