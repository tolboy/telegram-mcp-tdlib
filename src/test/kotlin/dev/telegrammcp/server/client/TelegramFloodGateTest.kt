package dev.telegrammcp.server.client

import dev.telegrammcp.server.exception.TelegramApiException
import dev.telegrammcp.server.exception.TelegramFloodGateException
import dev.telegrammcp.server.config.ResilienceConfig
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TelegramFloodGateTest {

    private val now = AtomicLong(0)
    private val gate = TelegramFloodGate { now.get() }

    private fun advanceSeconds(seconds: Long) = now.addAndGet(TimeUnit.SECONDS.toNanos(seconds))

    @Test
    fun `flood wait blocks only its scope until it expires`() {
        gate.record("chat 1", TelegramApiException(429, "Too Many Requests: retry after 10"))

        val blocked = assertFailsWith<TelegramFloodGateException> { gate.check("chat 1") }
        assertEquals(10L, blocked.retryAfterSeconds)
        assertTrue(blocked.message!!.contains("chat 1"))
        gate.check("chat 2")
        gate.check("method GetMe")

        advanceSeconds(9)
        now.addAndGet(1) // 9s + 1ns elapsed: still blocked, rounded up to 1s
        assertEquals(1L, assertFailsWith<TelegramFloodGateException> { gate.check("chat 1") }.retryAfterSeconds)
        advanceSeconds(1)
        gate.check("chat 1")
        assertEquals(emptySet(), gate.activeScopes())
    }

    @Test
    fun `non-flood errors and floods without a delay do not gate`() {
        gate.record("chat 1", TelegramApiException(400, "Can't access the chat"))
        gate.record("chat 1", TelegramApiException(500, "WORKER_BUSY_TOO_LONG_RETRY"))
        gate.record("chat 1", TelegramApiException(429, "Too Many Requests"))
        gate.check("chat 1")
    }

    @Test
    fun `longer wait wins`() {
        gate.record("chat 1", TelegramApiException(429, "Too Many Requests: retry after 30"))
        gate.record("chat 1", TelegramApiException(420, "SLOWMODE_WAIT_5"))
        assertEquals(30L, assertFailsWith<TelegramFloodGateException> { gate.check("chat 1") }.retryAfterSeconds)
    }

    @Test
    fun `scope is the target chat when the function has one and the method otherwise`() {
        assertEquals("chat -100", TelegramFloodGate.scopeOf(TdApi.SendMessage().apply { chatId = -100 }))
        assertEquals("chat 5", TelegramFloodGate.scopeOf(TdApi.GetChatHistory().apply { chatId = 5 }))
        assertEquals("method GetMe", TelegramFloodGate.scopeOf(TdApi.GetMe()))
        assertEquals("method SearchPublicChat", TelegramFloodGate.scopeOf(TdApi.SearchPublicChat("x")))
        assertEquals("method GetChat", TelegramFloodGate.scopeOf(TdApi.GetChat(0)), "chat 0 is not a chat")
    }

    // ---- service-level isolation --------------------------------------------------------

    private val client = mockk<SimpleTelegramClient>()
    private val requests = mutableListOf<TdApi.Function<*>>()
    private val breaker = CircuitBreaker.of("flood-test", ResilienceConfig.ignoringNonHealthFailures(CircuitBreakerConfig.ofDefaults()))
    private val service = TdLibClientService(
        client,
        RateLimiter.ofDefaults("flood-test"),
        breaker,
        SimpleMeterRegistry(),
        floodGate = gate,
    )

    /** Chat 1 and GetMe are flood-limited; everything else succeeds. */
    private fun floodChatOneAndGetMe() {
        every { client.send(any<TdApi.Function<TdApi.Object>>(), any<GenericResultHandler<TdApi.Object>>()) } answers {
            val request = firstArg<TdApi.Function<TdApi.Object>>()
            requests += request
            val floodLimited = (request is TdApi.PinChatMessage && request.chatId == 1L) || request is TdApi.GetMe
            val response: TdApi.Object =
                if (floodLimited) TdApi.Error(429, "Too Many Requests: retry after 60") else TdApi.Ok()
            secondArg<GenericResultHandler<TdApi.Object>>().onResult(Result.of(response))
        }
    }

    @Test
    fun `a 429 in one chat fails fast for that chat without blocking other chats`() {
        floodChatOneAndGetMe()

        val flood = assertFailsWith<TelegramApiException> { service.pinMessage(1, 10, false) }
        assertTrue(flood.isFloodWait)
        assertEquals(1, requests.size)

        // Same chat, any operation: rejected locally without reaching TDLib.
        val gated = assertFailsWith<TelegramFloodGateException> { service.leaveChat(1) }
        assertEquals(60L, gated.retryAfterSeconds)
        assertFailsWith<TelegramFloodGateException> { service.pinMessage(1, 11, false) }
        assertEquals(1, requests.size, "gated calls must not be sent")

        // Other chats are unaffected.
        assertTrue(service.pinMessage(2, 10, false))
        assertTrue(service.leaveChat(3))
        assertEquals(3, requests.size)
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state)
        assertEquals(0, breaker.metrics.numberOfFailedCalls)

        advanceSeconds(60)
        assertTrue(service.leaveChat(1), "chat 1 works again once the wait has elapsed")
    }

    @Test
    fun `a 429 on a chatless method gates only that method`() {
        floodChatOneAndGetMe()

        assertFailsWith<TelegramApiException> { service.getMe() }
        assertFailsWith<TelegramFloodGateException> { service.getMe() }
        assertEquals(1, requests.count { it is TdApi.GetMe })
        assertTrue(service.pinMessage(2, 10, false), "chat operations keep working")
    }
}
