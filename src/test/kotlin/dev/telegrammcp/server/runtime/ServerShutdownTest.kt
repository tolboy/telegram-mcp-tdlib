package dev.telegrammcp.server.runtime

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.context.ConfigurableApplicationContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TDLib's non-daemon threads keep the JVM — and under Docker the whole
 * container — alive after the client is gone, so every exit path has to end in
 * an explicit halt, whether or not the graceful close succeeds.
 */
class ServerShutdownTest {

    private class HaltRecorder {
        val codes = mutableListOf<Int>()
        private val halted = CountDownLatch(1)

        val halt: (Int) -> Unit = { code ->
            synchronized(codes) { codes += code }
            halted.countDown()
        }

        fun awaitHalt(): Boolean = halted.await(5, TimeUnit.SECONDS)

        fun firstCode(): Int = synchronized(codes) { codes.first() }
    }

    @Test
    fun `closes the attached context and then halts`() {
        val recorder = HaltRecorder()
        val context = mockk<ConfigurableApplicationContext>(relaxed = true)
        val shutdown = ServerShutdown(GRACE_MILLIS, recorder.halt)
        shutdown.attach(context)

        shutdown.requestShutdown("test", exitCode = 3)

        assertTrue(recorder.awaitHalt(), "shutdown never halted the JVM")
        assertEquals(3, recorder.firstCode())
        verify(exactly = 1) { context.close() }
    }

    @Test
    fun `halts even when stdin ends before the context exists`() {
        val recorder = HaltRecorder()
        val shutdown = ServerShutdown(GRACE_MILLIS, recorder.halt)

        shutdown.requestShutdown("stdin reached EOF during startup")

        assertTrue(recorder.awaitHalt(), "a shutdown without a context must still end the process")
        assertEquals(0, recorder.firstCode())
    }

    @Test
    fun `a context attached after the shutdown request is still closed`() {
        val recorder = HaltRecorder()
        val context = mockk<ConfigurableApplicationContext>(relaxed = true)
        val shutdown = ServerShutdown(GRACE_MILLIS, recorder.halt)

        shutdown.requestShutdown("stdin reached EOF during startup")
        shutdown.attach(context)

        assertTrue(recorder.awaitHalt())
        verify(timeout = 5_000, exactly = 1) { context.close() }
    }

    @Test
    fun `halts on the grace deadline when the graceful close hangs`() {
        val recorder = HaltRecorder()
        val release = CountDownLatch(1)
        val context = mockk<ConfigurableApplicationContext>()
        every { context.close() } answers { release.await(5, TimeUnit.SECONDS) }
        val shutdown = ServerShutdown(GRACE_MILLIS, recorder.halt)
        shutdown.attach(context)

        try {
            shutdown.requestShutdown("hung close", exitCode = 2)
            assertTrue(recorder.awaitHalt(), "the watchdog must halt a shutdown that does not finish")
            assertEquals(2, recorder.firstCode())
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `a failing close still ends the process`() {
        val recorder = HaltRecorder()
        val context = mockk<ConfigurableApplicationContext>()
        every { context.close() } throws IllegalStateException("context already broken")
        val shutdown = ServerShutdown(GRACE_MILLIS, recorder.halt)
        shutdown.attach(context)

        shutdown.requestShutdown("test")

        assertTrue(recorder.awaitHalt())
    }

    @Test
    fun `only the first request owns the exit`() {
        val recorder = HaltRecorder()
        val closes = AtomicInteger()
        val context = mockk<ConfigurableApplicationContext>()
        every { context.close() } answers { closes.incrementAndGet() }
        val shutdown = ServerShutdown(GRACE_MILLIS, recorder.halt)
        shutdown.attach(context)

        shutdown.requestShutdown("first")
        shutdown.requestShutdown("second")

        assertTrue(recorder.awaitHalt())
        assertEquals(1, closes.get())
    }

    private companion object {
        /** Short enough to keep the deadline test quick, long enough to be stable. */
        const val GRACE_MILLIS = 400L
    }
}
