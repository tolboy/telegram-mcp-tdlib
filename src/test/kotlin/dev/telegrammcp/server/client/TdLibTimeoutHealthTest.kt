package dev.telegrammcp.server.client

import dev.telegrammcp.server.config.ResilienceConfig
import dev.telegrammcp.server.config.TdLibProperties
import dev.telegrammcp.server.exception.TelegramTimeoutException
import dev.telegrammcp.server.exception.TelegramUnavailableException
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.ratelimiter.RateLimiter
import io.github.resilience4j.ratelimiter.RateLimiterConfig
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import it.tdlight.client.GenericResultHandler
import it.tdlight.client.Result as TdResult
import it.tdlight.client.SimpleTelegramClient
import it.tdlight.jni.TdApi
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F2: a single flood-waited scope must not open the account's breaker while
 * TDLib is connected; a disconnected TDLib, or one that hangs while still
 * reporting Ready, must.
 */
class TdLibTimeoutHealthTest {

    private val client = mockk<SimpleTelegramClient>()
    private val connection = TelegramConnectionStates()
    private val breaker = CircuitBreaker.of(
        "health-test",
        ResilienceConfig.ignoringNonHealthFailures(
            CircuitBreakerConfig.custom().slidingWindowSize(10).failureRateThreshold(50f).build(),
        ),
    )
    private val settings = TdLibProperties.Requests(
        timeout = Duration.ofMillis(40),
        localTimeoutGate = Duration.ZERO,
    )

    private fun service(noAnswerWindow: Duration = Duration.ofMinutes(3)) = TdLibClientService(
        client,
        RateLimiter.of("health-test", RateLimiterConfig.custom().limitForPeriod(10_000).build()),
        breaker,
        SimpleMeterRegistry(),
        messageSendTracker = MessageSendTracker(),
        floodGate = TelegramFloodGate(),
        requestSettings = settings,
        bulkhead = TdLibClientService.accountBulkhead("health-test", settings),
        timeoutHealth = TdLibClientServiceFactory(connection, SimpleMeterRegistry()).timeoutHealth(
            "default",
            settings.copy(breakerTimeoutScopes = 3, breakerNoAnswerWindow = noAnswerWindow),
        ),
    )

    /** TDLib never answers requests matching [hangs] and answers the rest with a user. */
    private fun tdlib(hangs: (TdApi.Function<*>) -> Boolean) {
        every { client.send(any<TdApi.Function<TdApi.Object>>(), any<GenericResultHandler<TdApi.Object>>()) } answers {
            if (!hangs(firstArg())) {
                secondArg<GenericResultHandler<TdApi.Object>>().onResult(TdResult.of(user()))
            }
        }
    }

    private fun user() = TdApi.User().apply {
        id = 7
        firstName = "T"
        lastName = ""
        usernames = null
        type = TdApi.UserTypeRegular()
        status = TdApi.UserStatusEmpty()
    }

    private fun ready() = connection.update("default", TdApi.ConnectionStateReady())

    private fun timesOut(block: () -> Unit) {
        val error = runCatching(block).exceptionOrNull()
        assertTrue(error is TelegramTimeoutException, "expected a timeout, got $error")
    }

    @Test
    fun `one flooded scope while Ready does not open the breaker`() {
        ready()
        // getMe is flood-waited inside TDLib; other requests are answered.
        tdlib { it is TdApi.GetMe }
        val service = service()

        repeat(15) {
            timesOut { service.getMe() }
            service.getUser(7)
        }
        // And without any other traffic in between.
        repeat(15) { timesOut { service.getMe() } }

        assertEquals(CircuitBreaker.State.CLOSED, breaker.state)
        assertEquals(0, breaker.metrics.numberOfFailedCalls)
    }

    @Test
    fun `timeouts across several scopes while Ready open the breaker`() {
        ready()
        tdlib { true }
        val service = service()
        val calls = listOf({ service.getMe() }, { service.getUser(7) }, { service.getChat(9) })

        var opened = false
        for (i in 0 until 30) {
            val error = runCatching { calls[i % calls.size]() }.exceptionOrNull()
            if (error is TelegramUnavailableException) { opened = true; break }
        }
        assertTrue(opened, "a TDLib that answers nothing in any scope must open the breaker")
        assertEquals(CircuitBreaker.State.OPEN, breaker.state)
    }

    @Test
    fun `no answer for the configured window while Ready opens the breaker`() {
        ready()
        tdlib { it is TdApi.GetMe }
        val service = service(noAnswerWindow = Duration.ofMillis(150))

        var opened = false
        for (i in 0 until 40) {
            val error = runCatching { service.getMe() }.exceptionOrNull()
            if (error is TelegramUnavailableException) { opened = true; break }
        }
        assertTrue(opened, "a scope that gets no answer for longer than the window must open the breaker")
    }

    @Test
    fun `timeouts while TDLib is not Ready open the breaker`() {
        connection.update("default", TdApi.ConnectionStateConnecting())
        tdlib { it is TdApi.GetMe }
        val service = service()

        var opened = false
        for (i in 0 until 15) {
            val error = runCatching { service.getMe() }.exceptionOrNull()
            if (error is TelegramUnavailableException) { opened = true; break }
        }
        assertTrue(opened, "a disconnected TDLib must open the breaker even for one scope")
    }

    @Test
    fun `an answer resets the streak and an idle account does not count as silent`() {
        val now = AtomicLong(0)
        val health = TdLibTimeoutHealth({ true }, 3, Duration.ofSeconds(180)) { now.get() }

        health.onDispatch()
        assertFalse(health.countsTowardBreaker("a"))
        assertFalse(health.countsTowardBreaker("b"))
        health.onAnswer()
        // The streak restarted: two more scopes are not enough.
        assertFalse(health.countsTowardBreaker("c"))
        assertFalse(health.countsTowardBreaker("a"))
        assertTrue(health.countsTowardBreaker("b"))

        health.onAnswer()
        // Idle for an hour, then one request that times out after 40 s.
        now.addAndGet(Duration.ofHours(1).toNanos())
        health.onDispatch()
        now.addAndGet(Duration.ofSeconds(40).toNanos())
        assertFalse(health.countsTowardBreaker("a"))
        // Still nothing answered 180 s after that first request.
        now.addAndGet(Duration.ofSeconds(140).toNanos())
        assertTrue(health.countsTowardBreaker("a"))
    }

    @Test
    fun `an account without a reported connection state counts as not Ready`() {
        assertFalse(connection.isReady("default"))
        ready()
        assertTrue(connection.isReady("default"))
        assertEquals("ConnectionStateReady", connection.stateName("default"))
    }

    @Test
    fun `Updating counts as reachable and the connecting states do not`() {
        val health = TdLibClientServiceFactory(connection, SimpleMeterRegistry()).timeoutHealth("default", settings)
        health.onDispatch()

        connection.update("default", TdApi.ConnectionStateUpdating())
        assertTrue(connection.isReachable("default"))
        assertFalse(connection.isReady("default"))
        // Catching up on missed updates after a reconnect: a flooded scope still does not count.
        assertFalse(health.countsTowardBreaker("send chat 1"))

        for (state in listOf(
            TdApi.ConnectionStateConnecting(),
            TdApi.ConnectionStateConnectingToProxy(),
            TdApi.ConnectionStateWaitingForNetwork(),
        )) {
            connection.update("default", state)
            assertFalse(connection.isReachable("default"), state.javaClass.simpleName)
            assertTrue(health.countsTowardBreaker("send chat 1"), state.javaClass.simpleName)
        }
    }
}
