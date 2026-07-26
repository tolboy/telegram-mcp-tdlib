package dev.telegrammcp.server.client

import dev.telegrammcp.server.exception.TdLibAuthException
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Startup must never wait for Telegram authentication — an unanswered MCP
 * `initialize` costs the whole connector — so the wait lives here, on the tool
 * call, where a failure can be reported as an ordinary tool error.
 */
class TelegramAuthGateTest {

    private fun gate(timeout: Duration = Duration.ofMillis(200)) =
        TelegramAuthGate("default", timeout)

    @Test
    fun `an authenticated account passes calls through`() {
        val gate = gate()
        val service = mockk<TelegramClientService>(relaxed = true)
        val gated = gate.gate(service)

        gate.markReady()
        gated.getMe()

        assertTrue(gate.isReady())
        verify(exactly = 1) { service.getMe() }
    }

    @Test
    fun `an unauthenticated account fails the call instead of the handshake`() {
        val gate = gate()
        val service = mockk<TelegramClientService>(relaxed = true)
        val gated = gate.gate(service)

        val error = assertFailsWith<TdLibAuthException> { gated.getMe() }

        assertTrue(error.message.contains("not authenticated"), error.message)
        assertTrue(error.message.contains("default"), error.message)
        verify(exactly = 0) { service.getMe() }
    }

    @Test
    fun `a recorded failure is reported verbatim and without waiting`() {
        val gate = gate(Duration.ofSeconds(30))
        val service = mockk<TelegramClientService>(relaxed = true)
        val gated = gate.gate(service)
        gate.markFailed("td.binlog is already in use")

        val startedAt = System.nanoTime()
        val error = assertFailsWith<TdLibAuthException> { gated.getMe() }

        assertTrue(error.message.contains("td.binlog is already in use"), error.message)
        assertTrue(
            Duration.ofNanos(System.nanoTime() - startedAt) < Duration.ofSeconds(5),
            "a known failure must not wait out the ready timeout",
        )
        assertFalse(gate.isReady())
        verify(exactly = 0) { service.getMe() }
    }

    @Test
    fun `the first recorded reason wins`() {
        val gate = gate()
        gate.markFailed("first")
        gate.markFailed("second")

        val error = assertFailsWith<TdLibAuthException> { gate.awaitReady() }

        assertTrue(error.message.contains("first"), error.message)
    }

    @Test
    fun `an unexpected failure after ready blocks later calls`() {
        val gate = gate()

        gate.markReady()
        gate.markFailed("session closed unexpectedly")

        assertFalse(gate.isReady())
        val error = assertFailsWith<TdLibAuthException> { gate.awaitReady() }
        assertTrue(error.message.contains("session closed unexpectedly"), error.message)
    }

    @Test
    fun `ready after failure cannot erase the original failure`() {
        val gate = gate()

        gate.markFailed("original failure")
        gate.markReady()

        assertFalse(gate.isReady())
        val error = assertFailsWith<TdLibAuthException> { gate.awaitReady() }
        assertTrue(error.message.contains("original failure"), error.message)
    }

    @Test
    fun `concurrent ready and failure signals always fail closed`() {
        repeat(100) {
            val gate = gate()
            val start = CountDownLatch(1)
            val readySignal = thread(isDaemon = true) {
                start.await()
                gate.markReady()
            }
            val failureSignal = thread(isDaemon = true) {
                start.await()
                gate.markFailed("concurrent failure")
            }

            start.countDown()
            readySignal.join()
            failureSignal.join()

            assertFalse(gate.isReady())
            gate.markReady()
            val error = assertFailsWith<TdLibAuthException> { gate.awaitReady() }
            assertTrue(error.message.contains("concurrent failure"), error.message)
        }
    }

    @Test
    fun `a waiting call resumes as soon as authentication completes`() {
        val gate = gate(Duration.ofSeconds(10))
        val service = mockk<TelegramClientService>(relaxed = true)
        val gated = gate.gate(service)
        val finished = CountDownLatch(1)

        thread(isDaemon = true) {
            gated.getMe()
            finished.countDown()
        }
        Thread.sleep(50)
        gate.markReady()

        assertTrue(finished.await(5, TimeUnit.SECONDS), "the call did not resume after authentication")
        verify(exactly = 1) { service.getMe() }
    }

    @Test
    fun `object methods do not wait for authentication`() {
        val gated = gate().gate(mockk<TelegramClientService>(relaxed = true))

        assertEquals(gated, gated)
        assertTrue(gated.toString().contains("default"))
    }
}
