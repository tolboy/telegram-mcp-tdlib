package dev.telegrammcp.server.client

import dev.telegrammcp.server.config.ResilienceConfig
import dev.telegrammcp.server.model.ChatActionType
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The facade keeps one executor and still reaches read, message, and admin calls. */
class TdLibClientDelegationTest {
    private val seen = mutableListOf<String>()
    private val client = mockk<SimpleTelegramClient>().also { client ->
        every { client.send(any<TdApi.Function<TdApi.Object>>(), any<GenericResultHandler<TdApi.Object>>()) } answers {
            seen += firstArg<TdApi.Function<TdApi.Object>>().javaClass.simpleName
            secondArg<GenericResultHandler<TdApi.Object>>().onResult(TdResult.of(TdApi.Ok()))
        }
    }

    private val service = TdLibClientService(
        client,
        RateLimiter.of(
            "delegation",
            RateLimiterConfig.custom().limitForPeriod(100).limitRefreshPeriod(Duration.ofSeconds(1)).timeoutDuration(Duration.ofSeconds(1)).build(),
        ),
        CircuitBreaker.of(
            "delegation",
            ResilienceConfig.ignoringNonHealthFailures(
                CircuitBreakerConfig.custom().slidingWindowSize(10).failureRateThreshold(50f).build(),
            ),
        ),
        SimpleMeterRegistry(),
    )

    @Test
    fun `read message and admin calls share the client`() {
        assertTrue(service.viewMessages(1L, listOf(2L)))
        assertTrue(service.sendChatAction(1L, ChatActionType.TYPING))
        assertTrue(service.leaveChat(1L))
        assertEquals(listOf("ViewMessages", "SendChatAction", "LeaveChat"), seen)
    }
}
