package dev.telegrammcp.server.security

import dev.telegrammcp.server.client.McpCallCancellation
import dev.telegrammcp.server.client.McpCallCancelledException
import dev.telegrammcp.server.client.TdLibRequestAwaiter
import dev.telegrammcp.server.exception.SendOutcomeUnknownException
import dev.telegrammcp.server.exception.TelegramBusyException
import dev.telegrammcp.server.exception.TelegramTimeoutException
import dev.telegrammcp.server.service.ClientConcurrencyGate
import it.tdlight.jni.TdApi
import jakarta.servlet.FilterChain
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class McpCancellationFilterTest {
    private val filter = McpCancellationFilter()

    @Test
    fun `cancelled notification releases only that session's wait and is not forwarded`() {
        val key = McpCancellationFilter.key("sess", "n:7")
        val other = McpCancellationFilter.key("other", "n:7")
        val future = CompletableFuture<TdApi.Object>()
        val started = CountDownLatch(1)
        val failure = CompletableFuture<Throwable>()
        val waiter = Thread {
            McpCallCancellation.bind(key)
            try {
                McpCallCancellation.listen {
                    started.countDown()
                    TdLibRequestAwaiter.await(future, TdApi.SendMessage().apply { chatId = 42 }, 30)
                }
            } catch (error: Throwable) {
                failure.complete(error)
            } finally {
                McpCallCancellation.unbind()
            }
        }
        waiter.start()
        assertTrue(started.await(2, TimeUnit.SECONDS))
        McpCallCancellation.cancel(other)
        Thread.sleep(80)
        assertFalse(failure.isDone, "Another session's request id must not stop this wait")

        val reached = AtomicBoolean()
        val request = post("""{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":7}}""")
        request.addHeader(McpSessionPrincipalBinder.HEADER, "sess")
        val response = MockHttpServletResponse()
        filter.doFilter(request, response, FilterChain { _, _ -> reached.set(true) })

        val error = assertIs<SendOutcomeUnknownException>(failure.get(2, TimeUnit.SECONDS))
        assertIs<McpCallCancelledException>(error.cause)
        assertFalse(error.healthFailure)
        assertFalse(future.isCancelled)
        assertFalse(future.isDone)
        assertEquals(202, response.status)
        assertFalse(reached.get())
        waiter.join(1000)
    }

    @Test
    fun `cancelling the local future is not reported as a Telegram cancellation`() {
        val future = CompletableFuture<TdApi.Object>()
        future.cancel(false)
        val error = assertIs<TelegramTimeoutException>(
            runCatching { TdLibRequestAwaiter.await(future, TdApi.GetMe(), 5) }.exceptionOrNull(),
        )
        assertEquals(TelegramTimeoutException.Reason.LOCAL_CANCEL, error.reason)
        assertTrue(error.requestSent)
        assertTrue(error.retryable)
        assertFalse(error.countsTowardBreaker)
    }

    @Test
    fun `a tools call binds its id and a cancel releases the client permit`() {
        val request = post("""{"jsonrpc":"2.0","id":"call-1","method":"tools/call","params":{"name":"get_me"}}""")
        request.addHeader(McpSessionPrincipalBinder.HEADER, "sess")
        val gate = ClientConcurrencyGate(1)
        val started = CountDownLatch(1)
        val call = Thread {
            filter.doFilter(request, MockHttpServletResponse(), FilterChain { _, _ ->
                McpCallCancellation.listen {
                    gate.run {
                        started.countDown()
                        McpCallCancellation.await(CompletableFuture<Unit>(), TimeUnit.SECONDS.toNanos(30))
                    }
                }
            })
        }
        call.start()
        assertTrue(started.await(2, TimeUnit.SECONDS))
        assertFailsWith<TelegramBusyException> { gate.run { } }
        McpCallCancellation.cancel(McpCancellationFilter.key("sess", "s:call-1"))
        call.join(2000)
        assertFalse(call.isAlive)
        assertTrue(gate.run { true })
    }

    private fun post(json: String) = MockHttpServletRequest("POST", "/mcp").apply {
        setContent(json.toByteArray())
        contentType = "application/json"
    }
}
