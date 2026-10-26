package dev.telegrammcp.server.config

import dev.telegrammcp.server.exception.InvalidToolInputException
import dev.telegrammcp.server.exception.SendOutcomeUnknownException
import dev.telegrammcp.server.exception.TelegramApiException
import dev.telegrammcp.server.exception.TelegramFloodGateException
import dev.telegrammcp.server.exception.TelegramTimeoutException
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
    fun `default waits fit inside the MCP client's request timeout`() {
        // MCP clients commonly give up on a tool call after 60 s; the server must
        // answer first, so every wait fits inside the tool-call deadline.
        val mcpClientTimeout = Duration.ofSeconds(60)
        val yml = requireNotNull(javaClass.getResource("/application.yml")).readText()
        fun seconds(name: String) = requireNotNull(
            Regex("""\$\{$name:(\d+)s}""").find(yml)?.groupValues?.get(1)?.toLong(),
        ) { "$name not found in application.yml" }.let(Duration::ofSeconds)

        val deadline = seconds("TDLIB_TOOL_DEADLINE")
        val request = seconds("TDLIB_REQUEST_TIMEOUT")
        assertTrue(deadline < mcpClientTimeout, "deadline $deadline must end before the client's $mcpClientTimeout")
        listOf(request, seconds("TDLIB_MESSAGE_SEND_TIMEOUT"), seconds("TDLIB_DOWNLOAD_TIMEOUT")).forEach {
            assertTrue(it <= deadline, "$it must not exceed the tool deadline $deadline")
        }
        // The code defaults match the shipped configuration.
        val defaults = TdLibProperties.Requests()
        assertEquals(deadline, defaults.toolDeadline)
        assertEquals(request, defaults.timeout)
        assertEquals(defaults.toolDeadline, dev.telegrammcp.server.client.ToolCallDeadline.DEFAULT_BUDGET)

        val slow = Regex("""slow-call-duration-threshold:\s*(\d+)s""").find(yml)?.groupValues?.get(1)?.toLong()
        requireNotNull(slow) { "slow-call-duration-threshold not found in application.yml" }
        assertTrue(Duration.ofSeconds(slow) < request, "slow calls must be detectable before the local timeout")
    }

    @Test
    fun `deadline, download and busy rejections do not open the breaker`() {
        val breaker = ResilienceConfig().telegramCircuitBreaker(registry())

        breaker.fail(10) {
            TelegramTimeoutException("GetChat", TelegramTimeoutException.Reason.TOOL_DEADLINE, true, true, 1_000)
        }
        breaker.fail(10) {
            TelegramTimeoutException("DownloadFile", TelegramTimeoutException.Reason.DOWNLOAD_TIMEOUT, true, true, 45_000)
        }
        breaker.fail(10) { SendOutcomeUnknownException(1, 0, TimeoutException(), deadlineExceeded = true) }

        assertEquals(CircuitBreaker.State.CLOSED, breaker.state)
        assertEquals(0, breaker.metrics.numberOfFailedCalls)
    }

    @Test
    fun `full request timeouts still open the breaker`() {
        listOf<() -> Throwable>(
            { TelegramTimeoutException("GetChat", TelegramTimeoutException.Reason.REQUEST_TIMEOUT, true, true, 40_000) },
            { SendOutcomeUnknownException(1, 0, TimeoutException()) },
        ).forEach { failure ->
            val breaker = ResilienceConfig().telegramCircuitBreaker(registry())
            breaker.fail(10, failure)
            assertEquals(CircuitBreaker.State.OPEN, breaker.state, failure().message)
        }
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
