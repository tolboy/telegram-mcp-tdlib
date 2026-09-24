package dev.telegrammcp.server.client

import dev.telegrammcp.server.exception.SendOutcomeUnknownException
import it.tdlight.jni.TdApi
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TdLibRequestAwaiterTest {
    @Test
    fun `send and forward timeouts do not imply failed delivery`() {
        val requests = listOf(
            TdApi.SendMessage().apply { chatId = 42 },
            TdApi.ForwardMessages().apply { chatId = 42 },
        )
        requests.forEach { request ->
            val pending = CompletableFuture<TdApi.Object>()
            val error = assertFailsWith<SendOutcomeUnknownException> {
                TdLibRequestAwaiter.await(pending, request, 0)
            }
            assertEquals(42L, error.chatId)
            assertTrue(error.cause is TimeoutException)
            // A local timeout must not prevent a later Telegram completion.
            assertTrue(pending.complete(TdApi.Ok()))
        }
    }

    @Test
    fun `read timeout stays a read failure`() {
        assertFailsWith<TimeoutException> {
            TdLibRequestAwaiter.await(CompletableFuture(), TdApi.GetMe(), 0)
        }
    }

    @Test
    fun `explicit upstream rejection is preserved`() {
        val rejected = IllegalStateException("rejected")
        val pending = CompletableFuture<TdApi.Object>().apply { completeExceptionally(rejected) }
        val error = assertFailsWith<IllegalStateException> {
            TdLibRequestAwaiter.await(pending, TdApi.SendMessage(), 0)
        }
        assertSame(rejected, error)
    }

    @Test
    fun `interrupted send preserves interrupt status and unknown outcome`() {
        try {
            Thread.currentThread().interrupt()
            assertFailsWith<SendOutcomeUnknownException> {
                TdLibRequestAwaiter.await(CompletableFuture(), TdApi.SendMessage(), 1)
            }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }
}
