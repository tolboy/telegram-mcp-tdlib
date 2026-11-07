package dev.telegrammcp.server.client

import it.tdlight.jni.TdApi
import dev.telegrammcp.server.exception.SendOutcomeUnknownException
import dev.telegrammcp.server.exception.TelegramApiException
import org.junit.jupiter.api.Test
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MessageSendTrackerTest {

    @Test
    fun `durable observer survives synchronous wait timeout`() {
        val tracker = MessageSendTracker()
        val pending = message(10, true)
        val observation = tracker.observeFinal(pending)
        assertFailsWith<SendOutcomeUnknownException> { tracker.awaitFinal(pending, 0) }
        tracker.onSucceeded(TdApi.UpdateMessageSendSucceeded(message(20, false), 10))
        assertEquals(20L, observation.get(1, TimeUnit.SECONDS).id)
    }

    @Test
    fun `observer and blocking waiter both receive an early outcome`() {
        val tracker = MessageSendTracker()
        val pending = message(10, true)
        tracker.onSucceeded(TdApi.UpdateMessageSendSucceeded(message(20, false), 10))
        assertEquals(20L, tracker.observeFinal(pending).get(1, TimeUnit.SECONDS).id)
        assertEquals(20L, tracker.awaitFinal(pending, 0).id)
    }

    @Test
    fun `multiple late observers and waiters retain the known final outcome`() {
        val tracker = MessageSendTracker()
        val pending = message(10, true)
        tracker.onSucceeded(TdApi.UpdateMessageSendSucceeded(message(20, false), 10))
        repeat(3) {
            assertEquals(20L, tracker.observeFinal(pending).get(1, TimeUnit.SECONDS).id)
            assertEquals(20L, tracker.awaitFinal(pending, 0).id)
        }
    }

    @Test
    fun `returns an already final message unchanged`() {
        val tracker = MessageSendTracker()
        val message = message(id = 10, sending = false)

        assertSame(message, tracker.awaitFinal(message, 1))
    }

    @Test
    fun `resolves an early success update to the final message id`() {
        val tracker = MessageSendTracker()
        val provisional = message(id = 10, sending = true)
        val final = message(id = 20, sending = false)
        tracker.onSucceeded(TdApi.UpdateMessageSendSucceeded(final, provisional.id))

        assertEquals(20, tracker.awaitFinal(provisional, 1).id)
    }

    @Test
    fun `waits for a success update when send is still pending`() {
        val tracker = MessageSendTracker()
        val provisional = message(id = 10, sending = true)
        val final = message(id = 20, sending = false)
        val result = CompletableFuture.supplyAsync { tracker.awaitFinal(provisional, 2) }

        tracker.onSucceeded(TdApi.UpdateMessageSendSucceeded(final, provisional.id))

        assertEquals(20, result.get(2, TimeUnit.SECONDS).id)
    }

    @Test
    fun `surfaces asynchronous send failure`() {
        val tracker = MessageSendTracker()
        val provisional = message(id = 10, sending = true)
        val failed = message(id = 10, sending = false)
        tracker.onFailed(TdApi.UpdateMessageSendFailed(failed, provisional.id, TdApi.Error(400, "rejected")))

        val error = assertFailsWith<TelegramApiException> { tracker.awaitFinal(provisional, 1) }
        assertTrue(error.message.contains("400"))
        assertTrue(error.message.contains("rejected"))
        assertEquals(400, error.errorCode)
        assertTrue(error.cause?.message.orEmpty().contains("chat"))
    }

    @Test
    fun `asynchronous flood failure keeps its retry delay`() {
        val tracker = MessageSendTracker()
        val provisional = message(id = 11, sending = true)
        val failed = message(id = 11, sending = false)
        tracker.onFailed(TdApi.UpdateMessageSendFailed(failed, provisional.id, TdApi.Error(429, "Too Many Requests: retry after 17")))

        val error = assertFailsWith<TelegramApiException> { tracker.awaitFinal(provisional, 1) }
        assertTrue(error.isFloodWait)
        assertTrue(error.wasNotExecuted)
        assertEquals(17L, error.retryAfterSeconds)
    }

    @Test
    fun `timeout is unknown and a late success can still be observed`() {
        val tracker = MessageSendTracker()
        val pending = message(10, true)
        val error = assertFailsWith<SendOutcomeUnknownException> { tracker.awaitFinal(pending, 0) }
        assertEquals(42L, error.chatId)
        assertEquals(10L, error.provisionalMessageId)

        val sent = message(20, false)
        tracker.onSucceeded(TdApi.UpdateMessageSendSucceeded(sent, pending.id))
        assertSame(sent, tracker.awaitFinal(pending, 0))
    }

    @Test
    fun `one caller timing out does not detach another caller waiting for the same send`() {
        val tracker = MessageSendTracker()
        val pending = message(10, true)
        val result = CompletableFuture<TdApi.Message>()
        val longWaiter = Thread {
            try {
                result.complete(tracker.awaitFinal(pending, 30))
            } catch (error: Throwable) {
                result.completeExceptionally(error)
            }
        }
        longWaiter.start()
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (longWaiter.state != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) Thread.sleep(5)
            assertEquals(Thread.State.TIMED_WAITING, longWaiter.state)

            assertFailsWith<SendOutcomeUnknownException> { tracker.awaitFinal(pending, 0) }
            val sent = message(20, false)
            tracker.onSucceeded(TdApi.UpdateMessageSendSucceeded(sent, pending.id))
            assertSame(sent, result.get(2, TimeUnit.SECONDS))
        } finally {
            longWaiter.interrupt()
            longWaiter.join(2000)
        }
    }

    @Test
    fun `all timed out callers release their pending wait and late success stays recoverable`() {
        val tracker = MessageSendTracker()
        val pending = message(10, true)
        repeat(10) { assertFailsWith<SendOutcomeUnknownException> { tracker.awaitFinal(pending, 0) } }
        val waiters = MessageSendTracker::class.java.getDeclaredField("waiters").apply { isAccessible = true }
        assertTrue((waiters.get(tracker) as Map<*, *>).isEmpty())

        val sent = message(20, false)
        tracker.onSucceeded(TdApi.UpdateMessageSendSucceeded(sent, pending.id))
        assertSame(sent, tracker.awaitFinal(pending, 0))
    }

    @Test
    fun `closing the session releases every waiter as unknown and ignores a later success`() {
        val tracker = MessageSendTracker()
        val pending = message(10, true)
        val observation = tracker.observeFinal(pending)
        val first = CompletableFuture<Throwable>()
        val second = CompletableFuture<Throwable>()
        val threads = listOf(first, second).map { slot ->
            Thread {
                try {
                    tracker.awaitFinal(pending, 30)
                    slot.complete(IllegalStateException("closed session returned a message"))
                } catch (error: Throwable) {
                    slot.complete(error)
                }
            }.also { it.start() }
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (threads.any { it.state != Thread.State.TIMED_WAITING } && System.nanoTime() < deadline) Thread.sleep(5)
        tracker.close()
        tracker.close()
        tracker.onSucceeded(TdApi.UpdateMessageSendSucceeded(message(20, false), pending.id))

        for (slot in listOf(first, second)) {
            val error = assertIs<SendOutcomeUnknownException>(slot.get(2, TimeUnit.SECONDS))
            assertIs<CancellationException>(error.cause)
            assertFalse(error.healthFailure)
        }
        val observed = assertFailsWith<ExecutionException> { observation.get(1, TimeUnit.SECONDS) }
        assertIs<SendOutcomeUnknownException>(observed.cause)
        assertFailsWith<SendOutcomeUnknownException> { tracker.awaitFinal(pending, 0) }
        threads.forEach { it.join(1000) }
    }

    @Test
    fun `closing cannot erase a definite later batch delivery or learn a new outcome`() {
        val tracker = MessageSendTracker()
        val first = message(10, true)
        val second = message(11, true)
        val delivered = message(21, false)
        tracker.onSucceeded(TdApi.UpdateMessageSendSucceeded(delivered, second.id))
        tracker.close()

        assertFailsWith<SendOutcomeUnknownException> { tracker.awaitFinal(first, 0) }
        assertSame(delivered, tracker.awaitFinal(second, 0))
        assertSame(delivered, tracker.observeFinal(second).get(1, TimeUnit.SECONDS))
        tracker.onSucceeded(TdApi.UpdateMessageSendSucceeded(message(20, false), first.id))
        assertFailsWith<SendOutcomeUnknownException> { tracker.awaitFinal(first, 0) }
        assertTrue(tracker.observeFinal(first).isCompletedExceptionally)
    }

    @Test
    fun `pre-close evidence expires and closed reads clear its retained cache`() {
        val tracker = MessageSendTracker()
        var now = 1L
        tracker.nanoTime = { now }
        val pending = message(10, true)
        val delivered = message(20, false)
        tracker.onSucceeded(TdApi.UpdateMessageSendSucceeded(delivered, pending.id))
        tracker.close()
        assertSame(delivered, tracker.awaitFinal(pending, 0))

        now += TimeUnit.MINUTES.toNanos(5) + 1
        assertFailsWith<SendOutcomeUnknownException> { tracker.awaitFinal(pending, 0) }
        val outcomes = MessageSendTracker::class.java.getDeclaredField("outcomes").apply { isAccessible = true }
        assertTrue((outcomes.get(tracker) as Map<*, *>).isEmpty())
    }

    private fun message(id: Long, sending: Boolean): TdApi.Message = TdApi.Message().apply {
        this.id = id
        this.chatId = 42
        this.sendingState = if (sending) TdApi.MessageSendingStatePending(1) else null
    }
}
