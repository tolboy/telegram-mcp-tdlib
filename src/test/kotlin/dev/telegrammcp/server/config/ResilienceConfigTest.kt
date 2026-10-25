package dev.telegrammcp.server.config

import dev.telegrammcp.server.exception.InvalidToolInputException
import dev.telegrammcp.server.exception.TelegramApiException
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.TimeoutException
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
        breaker.fail(5) { TelegramApiException(403, "CHAT_WRITE_FORBIDDEN") }
        breaker.fail(5) { TelegramApiException(404, "Not Found") }
        breaker.fail(5) { TelegramApiException(401, "Unauthorized") }
        breaker.fail(5) { InvalidToolInputException("bad") }

        assertEquals(CircuitBreaker.State.CLOSED, breaker.state)
        assertEquals(0, breaker.metrics.numberOfFailedCalls)
        assertEquals(0L, breaker.metrics.numberOfNotPermittedCalls)
        assertTrue(breaker.executeSupplier { true })
    }

    @Test
    fun `rate limits count as failures and open the breaker for 420 and 429`() {
        listOf(
            TelegramApiException(420, "FLOOD_WAIT_30"),
            TelegramApiException(420, "FLOOD_PREMIUM_WAIT_30"),
            TelegramApiException(429, "Too Many Requests: retry after 30"),
            TelegramApiException(429, "FLOOD_WAIT_30"),
        ).forEach { flood ->
            val breaker = ResilienceConfig().telegramCircuitBreaker(registry())

            breaker.fail(10) { flood }

            assertEquals(CircuitBreaker.State.OPEN, breaker.state, flood.message)
            assertFailsWith<CallNotPermittedException> { breaker.executeSupplier { "blocked" } }
        }
    }

    @Test
    fun `server errors and timeouts still open the breaker`() {
        val breaker = ResilienceConfig().telegramCircuitBreaker(registry())

        breaker.fail(5) { TelegramApiException(500, "Request aborted") }
        breaker.fail(5) { TimeoutException("no response") }

        assertEquals(CircuitBreaker.State.OPEN, breaker.state)
    }

    @Test
    fun `bean replaces the registry instance and per-account copies inherit the policy`() {
        val registry = registry()
        val breaker = ResilienceConfig().telegramCircuitBreaker(registry)
        assertSame(breaker, registry.circuitBreaker(ResilienceConfig.TELEGRAM_API))
        assertEquals(10, breaker.circuitBreakerConfig.slidingWindowSize)

        // TdLibConfig builds one breaker per account from the bean's config.
        val account = CircuitBreaker.of("telegram-work", breaker.circuitBreakerConfig)
        account.fail(10) { TelegramApiException(400, "PEER_ID_INVALID") }
        assertEquals(CircuitBreaker.State.CLOSED, account.state)
        account.fail(10) { TelegramApiException(429, "Too Many Requests: retry after 5") }
        assertEquals(CircuitBreaker.State.OPEN, account.state)
    }

    @Test
    fun `existing ignore rules are preserved`() {
        val base = CircuitBreakerConfig.custom().ignoreExceptions(IllegalStateException::class.java).build()
        val predicate = ResilienceConfig.ignoringCallerErrors(base).ignoreExceptionPredicate

        assertTrue(predicate.test(IllegalStateException("configured")))
        assertTrue(predicate.test(TelegramApiException(400, "BAD_REQUEST")))
        assertFalse(predicate.test(TelegramApiException(420, "FLOOD_WAIT_1")))
        assertFalse(predicate.test(TelegramApiException(503, "unavailable")))
        assertFalse(predicate.test(RuntimeException("boom")))
    }
}
