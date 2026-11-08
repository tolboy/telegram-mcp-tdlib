package dev.telegrammcp.server.client

import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Fake native actions make cross-owner close/acquire races reproducible without TDLib. */
class TdLibFactoryLifecycleTest {
    @Test
    fun `a new owner cannot acquire until another owner's actual close has drained`() {
        val gate = TdLibFactoryLifecycleGate()
        val closing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val acquired = CountDownLatch(1)
        try {
            val oldOwner = CompletableFuture.runAsync {
                gate.closeFactory { closing.countDown(); release.await(5, TimeUnit.SECONDS) }
            }
            assertTrue(closing.await(2, TimeUnit.SECONDS))
            val newOwner = CompletableFuture.supplyAsync {
                gate.acquire { acquired.countDown(); "new factory" }
            }
            assertFalse(acquired.await(150, TimeUnit.MILLISECONDS), "A queued acquisition must not overtake the native drain")
            release.countDown()
            oldOwner.get(2, TimeUnit.SECONDS)
            assertEquals("new factory", newOwner.get(2, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            gate.close()
        }
    }

    @Test
    fun `interrupting the closing owner neither interrupts native close nor releases the next acquire`() {
        val gate = TdLibFactoryLifecycleGate()
        val closing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val acquired = CountDownLatch(1)
        val stopped = CompletableFuture<Boolean>()
        val closer = Thread {
            try {
                gate.closeFactory {
                    closing.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    assertFalse(Thread.currentThread().isInterrupted, "The owned native worker must not inherit the caller's interrupt")
                }
                stopped.complete(Thread.currentThread().isInterrupted)
            } catch (error: Throwable) { stopped.completeExceptionally(error) }
        }
        closer.start()
        try {
            assertTrue(closing.await(2, TimeUnit.SECONDS))
            closer.interrupt()
            val next = CompletableFuture.supplyAsync { gate.acquire { acquired.countDown(); 42 } }
            assertFalse(acquired.await(150, TimeUnit.MILLISECONDS))
            assertFalse(stopped.isDone, "An interrupted caller must still wait for actual native close completion")
            release.countDown()
            assertTrue(stopped.get(2, TimeUnit.SECONDS), "Restore the caller's interrupt after native close has completed")
            assertEquals(42, next.get(2, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            closer.join(2_000)
            gate.close()
        }
    }

    @Test
    fun `close failure rejects later acquisitions while existing owners may still release`() {
        TdLibFactoryLifecycleGate().use { gate ->
            val failure = IllegalStateException("native drain failed")
            assertSame(failure, assertFailsWith<IllegalStateException> { gate.closeFactory { throw failure } })
            var called = false
            val rejected = assertFailsWith<IllegalStateException> { gate.acquire { called = true } }
            assertSame(failure, rejected.cause)
            assertFalse(called, "Never start a replacement receiver after an ambiguous close failure")
            gate.closeFactory { called = true }
            assertTrue(called, "Existing owners must still be able to drain")
            assertFailsWith<IllegalStateException> { gate.acquire { error("Failure must remain absorbing") } }
        }
    }

    @Test
    fun `a swallowed interrupted close poisons acquisition instead of starting a second receiver`() {
        TdLibFactoryLifecycleGate().use { gate ->
            assertFailsWith<IllegalStateException> { gate.closeFactory { Thread.currentThread().interrupt() } }
            assertFailsWith<IllegalStateException> { gate.acquire { error("Receiver is not confirmed closed") } }
            assertFalse(Thread.currentThread().isInterrupted, "A worker failure must not interrupt its caller")
        }
    }

    @Test
    fun `an already interrupted acquire caller retains its flag while the owned worker stays clean`() {
        TdLibFactoryLifecycleGate().use { gate ->
            Thread.currentThread().interrupt()
            try {
                assertEquals(42, gate.acquire { assertFalse(Thread.currentThread().isInterrupted); 42 })
                assertTrue(Thread.currentThread().isInterrupted)
            } finally { Thread.interrupted() }
        }
    }

    @Test
    fun `reentrant worker calls cannot deadlock or acquire a receiver from inside close`() {
        TdLibFactoryLifecycleGate().use { gate ->
            assertEquals(42, gate.acquire { gate.acquire { 42 } })
            gate.closeFactory {
                assertFailsWith<IllegalStateException> { gate.acquire { error("Must not acquire while closing") } }
            }
            assertEquals(43, gate.acquire { 43 })
        }
    }
}
