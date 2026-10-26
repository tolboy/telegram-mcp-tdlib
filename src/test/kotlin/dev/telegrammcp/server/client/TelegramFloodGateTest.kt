package dev.telegrammcp.server.client

import dev.telegrammcp.server.exception.TelegramApiException
import dev.telegrammcp.server.exception.TelegramFloodGateException
import dev.telegrammcp.server.config.ResilienceConfig
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.ratelimiter.RateLimiter
import io.github.resilience4j.ratelimiter.RateLimiterConfig
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import it.tdlight.client.GenericResultHandler
import it.tdlight.client.Result
import it.tdlight.client.SimpleTelegramClient
import it.tdlight.jni.TdApi
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
        gate.record("chat 1", TelegramApiException(400, "PEER_FLOOD"))
        gate.check("chat 1")
    }

    @Test
    fun `retry after 0 is reported and gated as one second`() {
        val zero = TelegramApiException(429, "Too Many Requests: retry after 0")
        assertEquals(1L, zero.retryAfterSeconds, "retry_after_seconds is never reported as 0")
        gate.record("method SendMessage chat 1", zero)
        assertEquals(
            1L,
            assertFailsWith<TelegramFloodGateException> { gate.check("method SendMessage chat 1") }.retryAfterSeconds,
        )
        advanceSeconds(1)
        gate.check("method SendMessage chat 1")
    }

    @Test
    fun `longer wait wins`() {
        gate.record("chat 1", TelegramApiException(429, "Too Many Requests: retry after 30"))
        gate.record("chat 1", TelegramApiException(420, "SLOWMODE_WAIT_5"))
        assertEquals(30L, assertFailsWith<TelegramFloodGateException> { gate.check("chat 1") }.retryAfterSeconds)
    }

    @Test
    fun `writes are scoped by method and chat, reads by method across the account`() {
        assertEquals(
            "method SendMessage chat -100",
            TelegramFloodGate.scopeOf(TdApi.SendMessage().apply { chatId = -100 }),
        )
        assertEquals("method PinChatMessage chat 5", TelegramFloodGate.scopeOf(TdApi.PinChatMessage(5, 1, false, false)))
        assertEquals("method GetChatHistory", TelegramFloodGate.scopeOf(TdApi.GetChatHistory().apply { chatId = 5 }))
        assertEquals("method GetMessage", TelegramFloodGate.scopeOf(TdApi.GetMessage(5, 1)))
        assertEquals("method GetMe", TelegramFloodGate.scopeOf(TdApi.GetMe()))
        assertEquals("method SearchPublicChat", TelegramFloodGate.scopeOf(TdApi.SearchPublicChat("x")))
        assertEquals("method SetBio", TelegramFloodGate.scopeOf(TdApi.SetBio("x")), "chatless write")
        assertEquals("method LeaveChat", TelegramFloodGate.scopeOf(TdApi.LeaveChat(0)), "chat 0 is not a chat")
        assertEquals(
            "method RecognizeSpeech chat 5",
            TelegramFloodGate.scopeOf(TdApi.RecognizeSpeech(5, 1)),
            "not on the read allowlist, so scoped like a write",
        )
    }

    @Test
    fun `concurrent records and checks keep the longest wait and never lose an active gate`() {
        val threads = 16
        val rounds = 500
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        try {
            val futures = (0 until threads).map { worker ->
                pool.submit {
                    start.await()
                    repeat(rounds) { round ->
                        val seconds = 1 + (worker * rounds + round) % 120
                        gate.record("shared", TelegramApiException(429, "Too Many Requests: retry after $seconds"))
                        gate.record("worker $worker", TelegramApiException(429, "Too Many Requests: retry after $seconds"))
                        // Concurrent checks remove only expired entries; an active one must keep failing.
                        assertFailsWith<TelegramFloodGateException> { gate.check("shared") }
                        assertFailsWith<TelegramFloodGateException> { gate.check("worker $worker") }
                        gate.check("never flooded $worker")
                    }
                }
            }
            start.countDown()
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        assertEquals(120L, assertFailsWith<TelegramFloodGateException> { gate.check("shared") }.retryAfterSeconds)
        assertEquals((0 until threads).map { "worker $it" }.toSet() + "shared", gate.activeScopes())
        advanceSeconds(120)
        assertEquals(emptySet(), gate.activeScopes())
        gate.check("shared")
    }

    // ---- service-level isolation --------------------------------------------------------

    private val client = mockk<SimpleTelegramClient>()
    private val requests = java.util.Collections.synchronizedList(mutableListOf<TdApi.Function<*>>())
    private val breaker = CircuitBreaker.of("flood-test", ResilienceConfig.ignoringNonHealthFailures(CircuitBreakerConfig.ofDefaults()))
    private val rateLimiter = RateLimiter.of(
        "flood-test",
        RateLimiterConfig.custom()
            .limitForPeriod(5)
            .limitRefreshPeriod(Duration.ofHours(1))
            .timeoutDuration(Duration.ZERO)
            .build(),
    )
    private val tracker = MessageSendTracker()
    private val service = TdLibClientService(
        client,
        rateLimiter,
        breaker,
        SimpleMeterRegistry(),
        messageSendTracker = tracker,
        floodGate = gate,
    )

    /** Answers every TDLib request with [respond], recording it. */
    private fun answer(respond: (TdApi.Function<*>) -> TdApi.Object) {
        every { client.send(any<TdApi.Function<TdApi.Object>>(), any<GenericResultHandler<TdApi.Object>>()) } answers {
            val request = firstArg<TdApi.Function<TdApi.Object>>()
            requests += request
            secondArg<GenericResultHandler<TdApi.Object>>().onResult(Result.of(respond(request)))
        }
    }

    private fun flood(seconds: Long = 60) = TdApi.Error(429, "Too Many Requests: retry after $seconds")

    /** PinChatMessage in chat 1, GetMe and GetMessageViewers in chat 1 are flood-limited. */
    private fun floodChatOneAndGetMe() = answer { request ->
        when {
            request is TdApi.PinChatMessage && request.chatId == 1L -> flood()
            request is TdApi.GetMe -> flood()
            request is TdApi.GetMessageViewers && request.chatId == 1L -> flood()
            request is TdApi.GetMessageViewers -> TdApi.MessageViewers(emptyArray())
            else -> TdApi.Ok()
        }
    }

    @Test
    fun `a write flood gates that write in that chat only`() {
        floodChatOneAndGetMe()

        val flood = assertFailsWith<TelegramApiException> { service.pinMessage(1, 10, false) }
        assertTrue(flood.isFloodWait)
        assertEquals(1, requests.size)

        // Same write, same chat: rejected locally without reaching TDLib.
        val gated = assertFailsWith<TelegramFloodGateException> { service.pinMessage(1, 11, false) }
        assertEquals(60L, gated.retryAfterSeconds)
        assertEquals("method PinChatMessage chat 1", gated.scope)
        assertEquals(1, requests.size, "gated calls must not be sent")

        // Other chats and other operations in the same chat are unaffected.
        assertTrue(service.pinMessage(2, 10, false))
        assertTrue(service.leaveChat(1))
        assertTrue(service.unpinMessage(1, 10))
        assertEquals(4, requests.size)
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state)
        assertEquals(0, breaker.metrics.numberOfFailedCalls)

        advanceSeconds(60)
        assertFailsWith<TelegramApiException>("reaches TDLib again once the wait has elapsed") {
            service.pinMessage(1, 11, false)
        }
        assertEquals(5, requests.size)
    }

    @Test
    fun `a write flood never blocks reads in the same chat`() {
        floodChatOneAndGetMe()
        assertFailsWith<TelegramApiException> { service.pinMessage(1, 10, false) }

        assertEquals(emptyList(), service.getMessageViewers(2, 10))
        assertEquals(1, requests.count { it is TdApi.GetMessageViewers })
    }

    @Test
    fun `a read flood gates that read across all chats but not writes`() {
        floodChatOneAndGetMe()

        assertFailsWith<TelegramApiException> { service.getMessageViewers(1, 10) }
        val gated = assertFailsWith<TelegramFloodGateException> { service.getMessageViewers(2, 10) }
        assertEquals("method GetMessageViewers", gated.scope)
        assertEquals(1, requests.count { it is TdApi.GetMessageViewers }, "read limits are account-wide")
        assertTrue(service.leaveChat(1), "writes keep working")
    }

    @Test
    fun `a 429 on a chatless method gates only that method`() {
        floodChatOneAndGetMe()

        assertFailsWith<TelegramApiException> { service.getMe() }
        assertFailsWith<TelegramFloodGateException> { service.getMe() }
        assertEquals(1, requests.count { it is TdApi.GetMe })
        assertTrue(service.pinMessage(2, 10, false), "chat operations keep working")
    }

    @Test
    fun `gated calls do not consume rate-limiter permits`() {
        floodChatOneAndGetMe()
        assertFailsWith<TelegramApiException> { service.getMe() }
        val permits = rateLimiter.metrics.availablePermissions

        repeat(20) { assertFailsWith<TelegramFloodGateException> { service.getMe() } }

        assertEquals(permits, rateLimiter.metrics.availablePermissions, "the gate runs before the rate limiter")
        assertEquals(1, requests.size)
        assertTrue(service.leaveChat(3), "permits are still available for real requests")
        assertEquals(permits - 1, rateLimiter.metrics.availablePermissions)
    }

    private fun pending(chatId: Long, id: Long) = TdApi.Message().apply {
        this.chatId = chatId
        this.id = id
        sendingState = TdApi.MessageSendingStatePending()
    }

    private fun sent(chatId: Long, id: Long) = TdApi.Message().apply {
        this.chatId = chatId
        this.id = id
    }
}
