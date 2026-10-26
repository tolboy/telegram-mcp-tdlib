package dev.telegrammcp.server.client

import dev.telegrammcp.server.exception.ForwardPartiallyDeliveredException
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
        assertEquals("send chat -100", TelegramFloodGate.scopeOf(TdApi.SendMessage().apply { chatId = -100 }))
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
    fun `every kind of send to a chat shares one scope`() {
        val sends = listOf(
            TdApi.SendMessage().apply { chatId = 7 },
            TdApi.SendMessageAlbum().apply { chatId = 7 },
            TdApi.ForwardMessages().apply { chatId = 7 },
            TdApi.ResendMessages().apply { chatId = 7 },
            TdApi.SendInlineQueryResultMessage().apply { chatId = 7 },
            TdApi.SendBotStartMessage().apply { chatId = 7 },
        )
        sends.forEach { assertEquals("send chat 7", TelegramFloodGate.scopeOf(it), it.javaClass.simpleName) }
        assertEquals("send chat 8", TelegramFloodGate.scopeOf(TdApi.SendMessageAlbum().apply { chatId = 8 }))

        // Slow mode hit by an album also blocks a single message to that chat.
        gate.record(TelegramFloodGate.scopeOf(sends[1]), TelegramApiException(420, "SLOWMODE_WAIT_30"))
        assertFailsWith<TelegramFloodGateException> { gate.check(TelegramFloodGate.scopeOf(sends[0])) }
        gate.check("send chat 8")
    }

    @Test
    fun `account-wide writes are keyed by method only and downloads by file`() {
        assertEquals("method JoinChat", TelegramFloodGate.scopeOf(TdApi.JoinChat(5)))
        assertEquals("method JoinChat", TelegramFloodGate.scopeOf(TdApi.JoinChat(6)))
        assertEquals("method JoinChatByInviteLink", TelegramFloodGate.scopeOf(TdApi.JoinChatByInviteLink("https://t.me/+x")))
        assertEquals("method AddChatMember", TelegramFloodGate.scopeOf(TdApi.AddChatMember(5, 1, 0)))
        assertEquals("method AddChatMembers", TelegramFloodGate.scopeOf(TdApi.AddChatMembers(5, longArrayOf(1))))
        assertEquals("method DownloadFile file 7", TelegramFloodGate.scopeOf(TdApi.DownloadFile(7, 1, 0, 0, true)))
        assertEquals("method DownloadFile file 8", TelegramFloodGate.scopeOf(TdApi.DownloadFile(8, 1, 0, 0, true)))
    }

    @Test
    fun `a local timeout gates briefly and never shortens a flood wait`() {
        gate.recordLocalTimeout("method GetMe", Duration.ofSeconds(10))
        val gated = assertFailsWith<TelegramFloodGateException> { gate.check("method GetMe") }
        assertTrue(gated.afterLocalTimeout)
        assertEquals(10L, gated.retryAfterSeconds)
        assertTrue("did not complete in time" in gated.message!!, gated.message)

        gate.record("chat 1", TelegramApiException(429, "Too Many Requests: retry after 30"))
        gate.recordLocalTimeout("chat 1", Duration.ofSeconds(10))
        val flood = assertFailsWith<TelegramFloodGateException> { gate.check("chat 1") }
        assertEquals(30L, flood.retryAfterSeconds)
        assertEquals(false, flood.afterLocalTimeout)

        gate.recordLocalTimeout("chat 2", Duration.ZERO)
        gate.check("chat 2")
        advanceSeconds(10)
        gate.check("method GetMe")
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

    @Test
    fun `expiry removal racing with new records never loses an active gate`() {
        // One worker keeps advancing the shared clock, so entries expire while
        // other workers check (removing expired entries) and record new ones.
        val clock = AtomicLong(0)
        val racing = TelegramFloodGate { clock.get() }
        val second = TimeUnit.SECONDS.toNanos(1)
        val maxAdvance = clock.get() + 1_000 * second
        val workers = 8
        val rounds = 2_000
        val pool = Executors.newFixedThreadPool(workers + 1)
        val start = CountDownLatch(1)
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        try {
            val ticker = pool.submit {
                start.await()
                // Advance at most 1 000 s in total: short (1 s) entries keep
                // expiring during the race, but the 1 000 000 s waits can never
                // expire, however many ticks a slow CI machine manages.
                while (running.get()) {
                    if (clock.get() < maxAdvance) clock.addAndGet(second / 2)
                    Thread.onSpinWait()
                }
            }
            val futures = (0 until workers).map { worker ->
                pool.submit {
                    start.await()
                    repeat(rounds) { round ->
                        val scope = if (round % 2 == 0) "shared" else "worker $worker"
                        racing.record(scope, TelegramApiException(429, "Too Many Requests: retry after 1000000"))
                        // The clock moves 0.5 s per tick, capped at 1 000 s; a 1 000 000 s
                        // wait cannot expire within the test, so it must stay visible to every check.
                        assertFailsWith<TelegramFloodGateException>("lost gate for $scope") { racing.check(scope) }
                        // Short entries expire under the ticker; removal must not touch the long ones.
                        racing.record("short $worker", TelegramApiException(420, "FLOOD_WAIT_1"))
                        runCatching { racing.check("short $worker") }
                        racing.recordLocalTimeout("short shared", Duration.ofSeconds(1))
                        runCatching { racing.check("short shared") }
                    }
                }
            }
            start.countDown()
            futures.forEach { it.get(60, TimeUnit.SECONDS) }
            running.set(false)
            ticker.get(10, TimeUnit.SECONDS)
        } finally {
            running.set(false)
            pool.shutdownNow()
        }

        assertFailsWith<TelegramFloodGateException> { racing.check("shared") }
        (0 until workers).forEach { worker ->
            assertFailsWith<TelegramFloodGateException> { racing.check("worker $worker") }
        }
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
    private val meters = SimpleMeterRegistry()
    private val service = TdLibClientService(
        client,
        rateLimiter,
        breaker,
        meters,
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

    /** Forwards three messages to chat 9; [outcome] decides each provisional message's final state. */
    private fun forwardWith(outcome: (index: Int) -> TdApi.Error?) = answer { request ->
        when (request) {
            is TdApi.ForwardMessages -> {
                val provisional = (0 until 3).map { pending(9, 101L + it) }
                provisional.forEachIndexed { index, message ->
                    val error = outcome(index)
                    if (error == null) tracker.onSucceeded(TdApi.UpdateMessageSendSucceeded(sent(9, 201L + index), message.id))
                    else tracker.onFailed(TdApi.UpdateMessageSendFailed(message, message.id, error))
                }
                TdApi.Messages(3, provisional.toTypedArray())
            }
            else -> TdApi.Error(400, "Chat not found")
        }
    }

    @Test
    fun `a forward flood after a delivered message is not retryable`() {
        forwardWith { index -> if (index == 0) null else flood(7) }

        val partial = assertFailsWith<ForwardPartiallyDeliveredException> {
            service.forwardMessages(fromChatId = 5, toChatId = 9, messageIds = listOf(11, 12, 13))
        }
        assertEquals(listOf(11L), partial.deliveredMessageIds)
        assertEquals(listOf(12L, 13L), partial.unconfirmedMessageIds)
        assertEquals(7L, partial.retryAfterSeconds)

        val error = dev.telegrammcp.server.tool.ToolError.from(partial)
        assertEquals(false, error.retryable)
        assertEquals(7L, error.retry_after_seconds)
        assertTrue("Do not repeat the same call" in error.next_action)
        assertTrue("retry the unchanged call" !in error.next_action)

        assertEquals(listOf(11L), error.delivered_message_ids)
        assertEquals(listOf(12L, 13L), error.unconfirmed_message_ids)
        assertEquals(emptyList(), error.not_forwardable_message_ids)

        // The flood still gates further sends of any kind to that chat.
        assertEquals("send chat 9", assertFailsWith<TelegramFloodGateException> {
            service.forwardMessages(5, 9, listOf(12))
        }.scope)
        assertFailsWith<TelegramFloodGateException> { service.sendMessage(9, "hi", dev.telegrammcp.server.model.ParseMode.PLAIN, null, null) }
    }

    @Test
    fun `messages TDLib refuses to forward are listed separately from unconfirmed ones`() {
        answer { request ->
            when (request) {
                is TdApi.ForwardMessages -> {
                    val first = pending(9, 101)
                    val third = pending(9, 103)
                    tracker.onSucceeded(TdApi.UpdateMessageSendSucceeded(sent(9, 201), first.id))
                    tracker.onFailed(TdApi.UpdateMessageSendFailed(third, third.id, flood(7)))
                    TdApi.Messages(3, arrayOf(first, null, third))
                }
                else -> TdApi.Error(400, "Chat not found")
            }
        }

        val partial = assertFailsWith<ForwardPartiallyDeliveredException> { service.forwardMessages(5, 9, listOf(11, 12, 13)) }
        val error = dev.telegrammcp.server.tool.ToolError.from(partial)
        assertEquals(listOf(11L), error.delivered_message_ids)
        assertEquals(listOf(13L), error.unconfirmed_message_ids)
        assertEquals(listOf(12L), error.not_forwardable_message_ids)
        assertTrue("Do not retry not_forwardable_message_ids" in error.next_action)
        assertTrue("12" in partial.message!!)
    }

    @Test
    fun `a gated title lookup does not blank the title of a delivered message`() {
        answer { request ->
            when (request) {
                is TdApi.SendMessage -> sent(9, 301)
                is TdApi.GetChat -> TdApi.Chat().apply { id = 9; title = "Team" }
                else -> TdApi.Ok()
            }
        }
        // get_chat hit a flood wait earlier; the gate for GetChat is active.
        gate.record("method GetChat", TelegramApiException(429, "Too Many Requests: retry after 60"))
        assertFailsWith<TelegramFloodGateException> { gate.check("method GetChat") }

        val message = service.sendMessage(9, "hi", dev.telegrammcp.server.model.ParseMode.PLAIN, null, null)
        assertEquals("Team", message.chatTitle)
        assertEquals(0.0, meters.find("telegram.lookup.degraded").counter()?.count() ?: 0.0)
    }

    @Test
    fun `a failed title lookup is counted, not hidden`() {
        answer { request ->
            when (request) {
                is TdApi.SendMessage -> sent(9, 301)
                is TdApi.GetChat -> flood(30)
                else -> TdApi.Ok()
            }
        }
        val message = service.sendMessage(9, "hi", dev.telegrammcp.server.model.ParseMode.PLAIN, null, null)
        assertNull(message.chatTitle)
        assertEquals(1.0, meters.get("telegram.lookup.degraded").tag("lookup", "getChat").counter().count())
        // The flood is still recorded, so explicit get_chat calls fail fast.
        assertFailsWith<TelegramFloodGateException> { gate.check("method GetChat") }
    }

    @Test
    fun `a forward flood before anything was delivered stays a retryable flood wait`() {
        forwardWith { flood(7) }

        val flood = assertFailsWith<TelegramApiException> { service.forwardMessages(5, 9, listOf(11, 12, 13)) }
        assertTrue(flood.isFloodWait)
        assertTrue(dev.telegrammcp.server.tool.ToolError.from(flood).retryable)
        assertNull(flood.cause as? ForwardPartiallyDeliveredException)
    }

    @Test
    fun `a flood on the first forward preserves the later delivered message ids`() {
        forwardWith { index -> if (index == 0) flood(7) else null }

        val partial = assertFailsWith<ForwardPartiallyDeliveredException> {
            ToolCallDeadline.run { service.forwardMessages(5, 9, listOf(11, 12, 13)) }
        }
        assertEquals(listOf(12L, 13L), partial.deliveredMessageIds)
        assertEquals(listOf(11L), partial.unconfirmedMessageIds)
        assertEquals(false, dev.telegrammcp.server.tool.ToolError.from(partial).retryable)
        assertEquals(7L, partial.retryAfterSeconds)
    }

    @Test
    fun `a flood on the first forward cannot make other in-flight messages retryable`() {
        answer { request ->
            when (request) {
                is TdApi.ForwardMessages -> {
                    val first = pending(9, 101)
                    tracker.onFailed(TdApi.UpdateMessageSendFailed(first, first.id, flood(7)))
                    TdApi.Messages(2, arrayOf(first, pending(9, 102)))
                }
                else -> TdApi.Error(400, "Chat not found")
            }
        }

        val partial = assertFailsWith<ForwardPartiallyDeliveredException> {
            ToolCallDeadline.run { service.forwardMessages(5, 9, listOf(11, 12)) }
        }
        assertEquals(emptyList(), partial.deliveredMessageIds)
        assertEquals(listOf(11L, 12L), partial.unconfirmedMessageIds)
        assertEquals(false, dev.telegrammcp.server.tool.ToolError.from(partial).retryable)
        assertTrue(partial.retryAfterSeconds!! >= 7)
    }
}
