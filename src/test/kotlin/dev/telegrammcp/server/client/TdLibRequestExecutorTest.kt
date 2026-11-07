package dev.telegrammcp.server.client

import dev.telegrammcp.server.config.ResilienceConfig
import dev.telegrammcp.server.config.TdLibProperties
import dev.telegrammcp.server.exception.SendOutcomeUnknownException
import dev.telegrammcp.server.exception.TelegramBusyException
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.ratelimiter.RateLimiter
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import it.tdlight.client.GenericResultHandler
import it.tdlight.client.Result
import it.tdlight.client.SimpleTelegramClient
import it.tdlight.jni.TdApi
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TdLibRequestExecutorTest {
    private val client = mockk<SimpleTelegramClient>()
    private val settings = TdLibProperties.Requests(timeout = Duration.ofMillis(50), maxConcurrentCalls = 1)
    private val bulkhead = TdLibClientService.accountBulkhead("request-executor", settings)
    private val requests = TdLibRequestExecutor(
        client, RateLimiter.ofDefaults("request-executor"),
        CircuitBreaker.of("request-executor", ResilienceConfig.ignoringNonHealthFailures(CircuitBreakerConfig.ofDefaults())),
        SimpleMeterRegistry(), null, TelegramFloodGate(), settings, bulkhead, TdLibTimeoutHealth.alwaysCounting(),
    )

    @Test
    fun `nested capabilities share a permit and a failure cannot leak nesting into the next call`() {
        every { client.send(any<TdApi.Function<TdApi.Object>>(), any<GenericResultHandler<TdApi.Object>>()) } answers {
            secondArg<GenericResultHandler<TdApi.Object>>().onResult(Result.of(TdApi.User().apply { id = 42 }))
        }

        assertFailsWith<IllegalStateException> {
            requests.withResilience("compound") {
                assertEquals(42L, requests.withResilience("getMe") { requests.send<TdApi.User>(TdApi.GetMe()) }.id)
                error("mapping failed after the nested request")
            }
        }

        // Another capability owns the only slot. A leaked thread-local nesting
        // marker would incorrectly let the next operation bypass the bulkhead.
        assertTrue(bulkhead.tryAcquirePermission(), "The failed operation must release its slot")
        try {
            assertFailsWith<TelegramBusyException> {
                requests.withResilience("blocked") { error("A saturated account must not dispatch") }
            }
        } finally {
            bulkhead.onComplete()
        }
        assertEquals(42L, requests.withResilience("getMe") { requests.send<TdApi.User>(TdApi.GetMe()) }.id)
    }

    @Test
    fun `a send callback still observes final delivery after the callers context and wait have ended`() {
        val callback = AtomicReference<GenericResultHandler<TdApi.Object>>()
        every { client.send(any<TdApi.Function<TdApi.Object>>(), any<GenericResultHandler<TdApi.Object>>()) } answers {
            callback.set(secondArg())
        }
        val observed = AtomicReference<List<Long>>()
        val request = TdApi.SendMessage().apply { chatId = 42 }
        assertFailsWith<SendOutcomeUnknownException> {
            SendObservationContext.observe({ messages -> observed.set(messages.map { it.id }) }) {
                requests.withResilience("sendMessage") { requests.send<TdApi.Message>(request) }
            }
        }
        assertNull(SendObservationContext.capture())
        assertNull(observed.get())

        callback.get().onResult(Result.of(TdApi.Message().apply { chatId = 42; id = 100 }))
        assertEquals(listOf(100L), observed.get())
        assertEquals(1, bulkhead.metrics.availableConcurrentCalls)
    }
}
