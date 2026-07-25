package dev.telegrammcp.server.runtime

import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The stdio contract has two halves that pull against each other: the server
 * must exit when its client closes stdin, and it must never read stdin itself
 * because the MCP transport owns that stream. These tests pin both.
 */
class StdinCloseWatcherTest {

    @Test
    fun `stream contents reach the reader untouched`() {
        val payload = """{"jsonrpc":"2.0","id":1,"method":"initialize"}""".toByteArray()
        val signals = AtomicInteger()
        val stream = EofSignalingInputStream(ByteArrayInputStream(payload)) { signals.incrementAndGet() }

        assertContentEquals(payload, stream.readBytes())
        assertEquals(1, signals.get())
    }

    @Test
    fun `single byte reads pass through and report the end`() {
        val signals = AtomicInteger()
        val stream = EofSignalingInputStream(ByteArrayInputStream(byteArrayOf(7))) { signals.incrementAndGet() }

        assertEquals(7, stream.read())
        assertEquals(0, signals.get())
        assertEquals(-1, stream.read())
        assertEquals(1, signals.get())
    }

    @Test
    fun `end of stream is reported once no matter how often it is read`() {
        val signals = AtomicInteger()
        val stream = EofSignalingInputStream(ByteArrayInputStream(ByteArray(0))) { signals.incrementAndGet() }

        repeat(5) { assertEquals(-1, stream.read()) }
        assertEquals(-1, stream.read(ByteArray(8), 0, 8))

        assertEquals(1, signals.get())
    }

    @Test
    fun `a broken stdin counts as the client going away`() {
        val signals = AtomicInteger()
        val failing = object : InputStream() {
            override fun read(): Int = throw IOException("pipe closed")
        }
        val stream = EofSignalingInputStream(failing) { signals.incrementAndGet() }

        assertFailsWith<IOException> { stream.read() }
        assertEquals(1, signals.get())
    }

    @Test
    fun `the installed watcher wraps stdin and ends the process at EOF`() {
        val originalStdin = System.`in`
        val halted = CountDownLatch(1)
        val shutdown = ServerShutdown(200) { halted.countDown() }
        try {
            System.setIn(ByteArrayInputStream(ByteArray(0)))
            installStdinCloseWatcher(shutdown)

            assertEquals(-1, System.`in`.read())

            assertTrue(halted.await(5, TimeUnit.SECONDS), "closing stdin must shut the server down")
        } finally {
            System.setIn(originalStdin)
        }
    }

    @Test
    fun `an empty read is not the end of the stream`() {
        val signals = AtomicInteger()
        val stream = EofSignalingInputStream(ByteArrayInputStream(byteArrayOf(1, 2, 3))) { signals.incrementAndGet() }

        assertEquals(0, stream.read(ByteArray(8), 0, 0))
        assertEquals(0, signals.get())
    }
}
