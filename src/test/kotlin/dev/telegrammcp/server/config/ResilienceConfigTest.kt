package dev.telegrammcp.server.config

import dev.telegrammcp.server.exception.InvalidToolInputException
import dev.telegrammcp.server.exception.TelegramApiException
import dev.telegrammcp.server.exception.TelegramFloodGateException
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ResilienceConfigTest {

    /** Mirrors the `telegram-api` circuit breaker settings in application.yml. */
    private fun registry() = CircuitBreakerRegistry.of(
        CircuitBreakerConfig.custom()
            .slidingWindowSize(10)
            .failureRateThreshold(50f)
            .waitDurationInOpenState(Duration.ofSeconds(30))
            .build(),
    )

    private fun CircuitBreaker.fail(times: Int, error: () -> Throwable) = repeat(times) {
        runCatching { executeSupplier<Unit> { throw error() } }
    }

    @Test
    fun `caller errors do not open the breaker`() {
        val breaker = ResilienceConfig().telegramCircuitBreaker(registry())

        breaker.fail(5) { TelegramApiException(400, "MESSAGE_TOO_LONG") }
        breaker.fail(5) { TelegramApiException(400, "Can't access the chat") }
        breaker.fail(5) { TelegramApiException(403, "CHAT_WRITE_FORBIDDEN") }
        breaker.fail(5) { TelegramApiException(404, "Not Found") }
        breaker.fail(5) { TelegramApiException(401, "Unauthorized") }
        breaker.fail(5) { TelegramApiException(500, "Topic not found") }
        breaker.fail(5) { TelegramApiException(500, "Unknown parse mode") }
        breaker.fail(5) { InvalidToolInputException("bad") }

        assertEquals(CircuitBreaker.State.CLOSED, breaker.state)
        assertEquals(0, breaker.metrics.numberOfFailedCalls)
        assertEquals(0L, breaker.metrics.numberOfNotPermittedCalls)
        assertTrue(breaker.executeSupplier { true })
    }

    @Test
    fun `flood waits and flood-gate rejections never open the breaker`() {
        val breaker = ResilienceConfig().telegramCircuitBreaker(registry())

        breaker.fail(10) { TelegramApiException(429, "Too Many Requests: retry after 30") }
        breaker.fail(10) { TelegramApiException(420, "FLOOD_WAIT_30") }
        breaker.fail(10) { TelegramApiException(420, "SLOWMODE_WAIT_10") }
        breaker.fail(10) { TelegramFloodGateException("chat 1", 30) }

        assertEquals(CircuitBreaker.State.CLOSED, breaker.state)
        assertEquals(0, breaker.metrics.numberOfFailedCalls)
        assertTrue(breaker.executeSupplier { true }, "other operations of the account keep working")
    }

    @Test
    fun `slow flood waits do not count as slow calls`() {
        val clock = AtomicLong()
        val config = CircuitBreakerConfig.custom()
            .slidingWindowSize(10)
            .minimumNumberOfCalls(10)
            .slowCallDurationThreshold(Duration.ofSeconds(10))
            .slowCallRateThreshold(80f)
            .currentTimestampFunction({ clock.get() }, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val breaker = ResilienceConfig().telegramCircuitBreaker(CircuitBreakerRegistry.of(config))
        repeat(10) {
            runCatching {
                breaker.executeSupplier<Unit> {
                    clock.addAndGet(60) // TDLib waited a minute before giving up
                    throw TelegramApiException(429, "Too Many Requests: retry after 60")
                }
            }
        }
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state)
        assertEquals(0, breaker.metrics.numberOfSlowCalls)
    }

    @Test
    fun `timeouts outlast TDLib's internal flood wait budget`() {
        // TDLib waits out flood waits internally until their total exceeds
        // NetQuery::total_timeout_limit_ (60 s) and only then returns 429.
        val tdlibInternalWaitLimit = 60L
        val timeout = dev.telegrammcp.server.client.TdLibClientService.TIMEOUT_SECONDS
        assertTrue(timeout > tdlibInternalWaitLimit, "timeout $timeout s must exceed TDLib's 60 s internal wait")
        assertTrue(dev.telegrammcp.server.client.TdLibClientService.MESSAGE_SEND_TIMEOUT_SECONDS > tdlibInternalWaitLimit)

        val yml = requireNotNull(javaClass.getResource("/application.yml")).readText()
        val slow = Regex("""slow-call-duration-threshold:\s*(\d+)s""").find(yml)?.groupValues?.get(1)?.toLong()
        requireNotNull(slow) { "slow-call-duration-threshold not found in application.yml" }
        assertTrue(slow > tdlibInternalWaitLimit, "a success after TDLib's internal waits must not count as slow")
        assertTrue(slow < timeout, "slow calls must be detectable before the local timeout")
    }

    @Test
    fun `cancellations do not open the breaker`() {
        val breaker = ResilienceConfig().telegramCircuitBreaker(registry())

        breaker.fail(10) { TelegramApiException(-1, "Canceled") }
        breaker.fail(10) { TelegramApiException(406, "Request canceled") }
        breaker.fail(10) { TelegramApiException(500, "Request aborted") }

        assertEquals(CircuitBreaker.State.CLOSED, breaker.state)
    }

    @Test
    fun `server errors and timeouts still open the breaker`() {
        listOf<() -> Throwable>(
            { TelegramApiException(500, "WORKER_BUSY_TOO_LONG_RETRY") },
            { TelegramApiException(500, "Receive invalid response") },
            { TelegramApiException(503, "Service Unavailable") },
            { TimeoutException("no response") },
        ).forEach { failure ->
            val breaker = ResilienceConfig().telegramCircuitBreaker(registry())
            breaker.fail(10, failure)
            assertEquals(CircuitBreaker.State.OPEN, breaker.state, failure().message)
            assertFailsWith<CallNotPermittedException> { breaker.executeSupplier { "blocked" } }
        }
    }

    @Test
    fun `bean replaces the registry instance and per-account breakers come from the registry`() {
        val registry = registry()
        val breaker = ResilienceConfig().telegramCircuitBreaker(registry)
        assertSame(breaker, registry.circuitBreaker(ResilienceConfig.TELEGRAM_API))
        assertEquals(10, breaker.circuitBreakerConfig.slidingWindowSize)

        // TdLibConfig creates one breaker per account through the registry.
        val account = registry.circuitBreaker("telegram-work", breaker.circuitBreakerConfig)
        assertTrue(registry.find("telegram-work").isPresent)
        account.fail(10) { TelegramApiException(400, "PEER_ID_INVALID") }
        account.fail(10) { TelegramApiException(429, "Too Many Requests: retry after 5") }
        assertEquals(CircuitBreaker.State.CLOSED, account.state)
        account.fail(10) { TelegramApiException(500, "WORKER_BUSY_TOO_LONG_RETRY") }
        assertEquals(CircuitBreaker.State.OPEN, account.state)
    }

    @Test
    fun `existing ignore rules are preserved`() {
        val base = CircuitBreakerConfig.custom().ignoreExceptions(IllegalStateException::class.java).build()
        val predicate = ResilienceConfig.ignoringNonHealthFailures(base).ignoreExceptionPredicate

        assertTrue(predicate.test(IllegalStateException("configured")))
        assertTrue(predicate.test(TelegramApiException(400, "BAD_REQUEST")))
        assertTrue(predicate.test(TelegramApiException(420, "FLOOD_WAIT_1")))
        assertFalse(predicate.test(TelegramApiException(503, "unavailable")))
        assertFalse(predicate.test(RuntimeException("boom")))
    }
}
