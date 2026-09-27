package dev.telegrammcp.server.client

import it.tdlight.jni.TdApi
import dev.telegrammcp.server.exception.SendOutcomeUnknownException
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

        val error = assertFailsWith<IllegalStateException> { tracker.awaitFinal(provisional, 1) }
        assertTrue(error.message.orEmpty().contains("400"))
        assertTrue(error.message.orEmpty().contains("rejected"))
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

    private fun message(id: Long, sending: Boolean): TdApi.Message = TdApi.Message().apply {
        this.id = id
        this.chatId = 42
        this.sendingState = if (sending) TdApi.MessageSendingStatePending(1) else null
    }
}
