package dev.telegrammcp.server.client

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToolCallDeadlineTest {
    private val clock = AtomicLong(0)
    private fun advance(seconds: Long) = clock.addAndGet(TimeUnit.SECONDS.toNanos(seconds))

    init {
        ToolCallDeadline.nanoTime = { clock.get() }
    }

    @AfterEach
    fun restore() {
        ToolCallDeadline.nanoTime = System::nanoTime
    }

    @Test
    fun `no budget outside a tool call`() {
        assertFalse(ToolCallDeadline.isActive)
        assertNull(ToolCallDeadline.remainingNanos())
    }

    @Test
    fun `budget runs down and nested calls keep the outer budget`() {
        ToolCallDeadline.run(Duration.ofSeconds(50)) {
            advance(10)
            assertEquals(TimeUnit.SECONDS.toNanos(40), ToolCallDeadline.remainingNanos())
            ToolCallDeadline.run(Duration.ofSeconds(50)) {
                assertEquals(TimeUnit.SECONDS.toNanos(40), ToolCallDeadline.remainingNanos())
            }
        }
        assertNull(ToolCallDeadline.remainingNanos())
    }

    @Test
    fun `waiting for an approval does not use the budget`() {
        ToolCallDeadline.run(Duration.ofSeconds(50)) {
            advance(5)
            ToolCallDeadline.excluding { advance(120) }
            assertEquals(TimeUnit.SECONDS.toNanos(45), ToolCallDeadline.remainingNanos())
        }
    }

    @Test
    fun `dispatched writes are remembered for the call only`() {
        ToolCallDeadline.run(Duration.ofSeconds(50)) {
            assertFalse(ToolCallDeadline.writeDispatched())
            ToolCallDeadline.markWriteDispatched()
            assertTrue(ToolCallDeadline.writeDispatched())
        }
        ToolCallDeadline.run(Duration.ofSeconds(50)) { assertFalse(ToolCallDeadline.writeDispatched()) }
    }

    @Test
    fun `coroutine workers share the original remaining budget and write history`() {
        ToolCallDeadline.run(Duration.ofSeconds(50)) {
            val context = ToolCallDeadline.coroutineContext()
            advance(10)
            runBlocking(context) {
                withContext(Dispatchers.IO) {
                    ToolCallDeadline.run(Duration.ofSeconds(50)) {
                        assertEquals(TimeUnit.SECONDS.toNanos(40), ToolCallDeadline.remainingNanos())
                        advance(5)
                        ToolCallDeadline.markWriteDispatched()
                    }
                }
            }
            assertEquals(TimeUnit.SECONDS.toNanos(35), ToolCallDeadline.remainingNanos())
            assertTrue(ToolCallDeadline.writeDispatched())
        }
        runBlocking {
            withContext(Dispatchers.IO + ToolCallDeadline.coroutineContext()) {
                assertFalse(ToolCallDeadline.isActive)
            }
        }
    }

    @Test
    fun `capped narrows the budget, never widens it and restores the outer call`() {
        ToolCallDeadline.capped(Duration.ofSeconds(2)) {
            assertEquals(TimeUnit.SECONDS.toNanos(2), ToolCallDeadline.remainingNanos())
        }
        assertFalse(ToolCallDeadline.isActive)
        ToolCallDeadline.run(Duration.ofSeconds(50)) {
            advance(45)
            ToolCallDeadline.capped(Duration.ofSeconds(10)) {
                assertEquals(TimeUnit.SECONDS.toNanos(5), ToolCallDeadline.remainingNanos())
            }
            ToolCallDeadline.capped(Duration.ofSeconds(1)) {
                advance(1)
                assertEquals(0L, ToolCallDeadline.remainingNanos())
                ToolCallDeadline.markWriteDispatched()
            }
            // The outer budget kept running and learned about the write.
            assertEquals(TimeUnit.SECONDS.toNanos(4), ToolCallDeadline.remainingNanos())
            assertTrue(ToolCallDeadline.writeDispatched())
        }
        assertFalse(ToolCallDeadline.isActive)
    }
}
