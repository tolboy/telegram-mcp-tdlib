package dev.telegrammcp.server.service

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeout
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LateSendReceiptWriterTest {
    @Test
    fun `blocked disk work and a full queue never run on or block the callback thread`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val workerThread = AtomicReference<Thread>()
        val queuedWrites = AtomicInteger()
        val writer = LateSendReceiptWriter(queueCapacity = 1)
        try {
            assertTrue(writer.submit {
                workerThread.set(Thread.currentThread())
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
            })
            assertTrue(started.await(2, TimeUnit.SECONDS))
            assertNotEquals(Thread.currentThread(), workerThread.get())

            assertTimeout(Duration.ofSeconds(1)) {
                assertTrue(writer.submit { queuedWrites.incrementAndGet() })
                assertFalse(writer.submit { error("Rejected work must never run on the caller") })
            }
            assertEquals(0, queuedWrites.get())
        } finally {
            release.countDown()
            writer.close()
        }
        assertEquals(1, queuedWrites.get())
        assertFalse(writer.submit { error("Closed writer must reject work") })
    }

    @Test
    fun `one failed receipt does not stop later receipts and close drains accepted writes`() {
        val completed = CountDownLatch(1)
        LateSendReceiptWriter().use { writer ->
            assertTrue(writer.submit { throw IllegalStateException("disk failure") })
            assertTrue(writer.submit { completed.countDown() })
        }
        assertEquals(0L, completed.count)
    }
}
