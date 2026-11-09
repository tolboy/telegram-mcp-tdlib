package dev.telegrammcp.server.client

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.config.ResilienceConfig
import dev.telegrammcp.server.config.TdLibProperties
import dev.telegrammcp.server.exception.SendOutcomeUnknownException
import dev.telegrammcp.server.exception.TelegramTimeoutException
import dev.telegrammcp.server.service.SendOperationJournal
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.ratelimiter.RateLimiter
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import it.tdlight.client.GenericResultHandler
import it.tdlight.client.Result as TdResult
import it.tdlight.client.SimpleTelegramClient
import it.tdlight.jni.TdApi
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TdLibRequestCancellationTest {
    @TempDir lateinit var directory: Path
    private val client = mockk<SimpleTelegramClient>()
    private val floodGate = TelegramFloodGate()
    private val breaker = CircuitBreaker.of("local-cancel", ResilienceConfig.ignoringNonHealthFailures(CircuitBreakerConfig.ofDefaults()))
    private val settings = TdLibProperties.Requests(timeout = Duration.ofSeconds(30), maxConcurrentCalls = 1)
    private val bulkhead = TdLibClientService.accountBulkhead("local-cancel", settings)
    private val limiter = mockk<RateLimiter>().apply { every { reservePermission() } returns 0L }
    private fun executor(rateLimiter: RateLimiter = limiter) = TdLibRequestExecutor(
        client, rateLimiter, breaker, SimpleMeterRegistry(), MessageSendTracker(), floodGate, settings,
        bulkhead, TdLibTimeoutHealth.alwaysCounting(),
    )

    private fun answer(response: (TdApi.Function<*>) -> TdApi.Object?) {
        every { client.send(any<TdApi.Function<TdApi.Object>>(), any<GenericResultHandler<TdApi.Object>>()) } answers {
            response(firstArg())?.let { secondArg<GenericResultHandler<TdApi.Object>>().onResult(TdResult.of(it)) }
        }
    }

    private fun <T> bound(key: String, action: () -> T): T {
        McpCallCancellation.bind(key)
        return try { McpCallCancellation.listen(action) } finally { McpCallCancellation.unbind() }
    }

    @Test
    fun `cancelled dispatched sends remain unknown without poisoning breaker or flood scope`() {
        val request = TdApi.SendMessage().apply { chatId = 42 }
        val executor = executor()
        answer { McpCallCancellation.cancel("send"); null }
        val error = bound("send") {
            assertFailsWith<SendOutcomeUnknownException> {
                executor.withResilience("send") { executor.send<TdApi.Message>(request) }
            }
        }
        assertIs<McpCallCancelledException>(error.cause)
        assertFalse(error.healthFailure)
        assertEquals(0, breaker.metrics.numberOfFailedCalls)
        assertNull(floodGate.remainingSeconds(TelegramFloodGate.scopeOf(request)))
        assertEquals(1, bulkhead.metrics.availableConcurrentCalls)

        val delivered = TdApi.Message().apply { chatId = 42; id = 100 }
        answer { delivered }
        assertEquals(100L, executor.withResilience("send") { executor.send<TdApi.Message>(request) }.id)
    }

    @Test
    fun `cancelled dispatched reads preserve actual wait time and do not gate the next caller`() {
        val request = TdApi.GetMe()
        val executor = executor()
        answer { McpCallCancellation.cancel("read"); null }
        val error = bound("read") {
            assertFailsWith<TelegramTimeoutException> {
                executor.withResilience("read") { executor.send<TdApi.User>(request) }
            }
        }
        assertEquals(TelegramTimeoutException.Reason.LOCAL_CANCEL, error.reason)
        assertTrue(error.requestSent && error.retryable)
        assertTrue(error.waitedMillis < 2_000, "An immediate cancellation must not report the full 30-second wait budget")
        assertFalse(error.countsTowardBreaker)
        assertNull(error.retryAfterSeconds)
        assertEquals(0, breaker.metrics.numberOfFailedCalls)
        assertNull(floodGate.remainingSeconds(TelegramFloodGate.scopeOf(request)))
        answer { TdApi.User().apply { id = 100 } }
        assertEquals(100L, executor.withResilience("read") { executor.send<TdApi.User>(request) }.id)
    }

    @Test
    fun `cancel before dispatch records a rejected reservation without sending a mutation`() {
        val executor = executor()
        val journal = SendOperationJournal(directory, jacksonObjectMapper())
        val error = bound("before-send") {
            McpCallCancellation.cancel("before-send")
            assertFailsWith<TelegramTimeoutException> {
                ToolCallDeadline.run {
                    journal.send("work", "key", 42, "request", {}) {
                        executor.withResilience("send") { executor.send<TdApi.Message>(TdApi.SendMessage().apply { chatId = 42 }) }.id
                    }
                }
            }
        }
        assertEquals(TelegramTimeoutException.Reason.LOCAL_CANCEL, error.reason)
        assertFalse(error.requestSent)
        assertTrue(error.retryable)
        assertEquals("REJECTED", journal.status("work", "key", 42)["status"])
        verify(exactly = 0) { client.send(any<TdApi.Function<TdApi.Object>>(), any<GenericResultHandler<TdApi.Object>>()) }
        assertEquals(0, breaker.metrics.numberOfFailedCalls)
    }

    @Test
    fun `cancel before a later dispatch cannot make an earlier write safe to repeat`() {
        val executor = executor()
        val journal = SendOperationJournal(directory, jacksonObjectMapper())
        answer { TdApi.Ok() }
        val error = bound("after-write") {
            assertFailsWith<TelegramTimeoutException> {
                ToolCallDeadline.run {
                    journal.send("work", "key", 42, "request", {}) {
                        executor.withResilience("leave") { executor.send<TdApi.Ok>(TdApi.LeaveChat(42)) }
                        McpCallCancellation.cancel("after-write")
                        executor.withResilience("send") { executor.send<TdApi.Message>(TdApi.SendMessage().apply { chatId = 42 }) }.id
                    }
                }
            }
        }
        assertFalse(error.requestSent)
        assertFalse(error.retryable)
        assertEquals("UNKNOWN", journal.status("work", "key", 42)["status"])
        verify(exactly = 1) { client.send(any<TdApi.Function<TdApi.Object>>(), any<GenericResultHandler<TdApi.Object>>()) }
    }

    @Test
    fun `cancellation wakes a reserved rate-limiter wait and releases the bulkhead before dispatch`() {
        val reserved = CountDownLatch(1)
        val rateLimiter = mockk<RateLimiter>().apply {
            every { reservePermission() } answers { reserved.countDown(); TimeUnit.SECONDS.toNanos(10) }
        }
        val executor = executor(rateLimiter)
        val failure = CompletableFuture<Throwable>()
        val worker = Thread {
            try {
                bound("rate-wait") {
                    executor.withResilience("send") { executor.send<TdApi.Message>(TdApi.SendMessage().apply { chatId = 42 }) }
                }
            } catch (error: Throwable) { failure.complete(error) }
        }
        worker.start()
        try {
            assertTrue(reserved.await(2, TimeUnit.SECONDS))
            McpCallCancellation.cancel("rate-wait")
            val error = assertIs<TelegramTimeoutException>(failure.get(2, TimeUnit.SECONDS))
            assertEquals(TelegramTimeoutException.Reason.LOCAL_CANCEL, error.reason)
            assertFalse(error.requestSent)
            assertTrue(error.retryable)
            assertEquals(1, bulkhead.metrics.availableConcurrentCalls)
            assertEquals(0, breaker.metrics.numberOfFailedCalls)
            verify(exactly = 0) { client.send(any<TdApi.Function<TdApi.Object>>(), any<GenericResultHandler<TdApi.Object>>()) }
        } finally {
            worker.interrupt()
            worker.join(2_000)
        }
    }
}
