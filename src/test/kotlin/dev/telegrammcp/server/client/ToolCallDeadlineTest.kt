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
}
