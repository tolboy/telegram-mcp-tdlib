package dev.telegrammcp.server.client

import dev.telegrammcp.server.config.ResilienceConfig
import dev.telegrammcp.server.config.TdLibProperties
import dev.telegrammcp.server.exception.ForwardPartiallyDeliveredException
import dev.telegrammcp.server.exception.SendOutcomeUnknownException
import dev.telegrammcp.server.exception.TelegramBusyException
import dev.telegrammcp.server.exception.TelegramFloodGateException
import dev.telegrammcp.server.exception.TelegramTimeoutException
import dev.telegrammcp.server.model.ParseMode
import dev.telegrammcp.server.tool.ToolError
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** N1/N2: every wait of a tool call fits inside its deadline; the account bulkhead fails fast. */
class TdLibDeadlineTest {

    private val client = mockk<SimpleTelegramClient>()
    private val breaker = CircuitBreaker.of(
        "deadline-test",
        ResilienceConfig.ignoringNonHealthFailures(
            CircuitBreakerConfig.custom().slidingWindowSize(10).failureRateThreshold(50f).build(),
        ),
    )
    private val tracker = MessageSendTracker()
    private val gate = TelegramFloodGate()

    private fun limiter(perPeriod: Int = 1000, period: Duration = Duration.ofSeconds(1), timeout: Duration = Duration.ofSeconds(5)) =
        RateLimiter.of(
            "deadline-test",
            RateLimiterConfig.custom().limitForPeriod(perPeriod).limitRefreshPeriod(period).timeoutDuration(timeout).build(),
        )

    private fun service(
        settings: TdLibProperties.Requests = TdLibProperties.Requests(),
        rateLimiter: RateLimiter = limiter(),
    ) = TdLibClientService(
        client, rateLimiter, breaker, SimpleMeterRegistry(),
        messageSendTracker = tracker, floodGate = gate, requestSettings = settings,
        bulkhead = TdLibClientService.accountBulkhead("telegram-test", settings),
    )

    /** TDLib answers [respond]'s result, or never when it returns null. */
    private fun answer(respond: (TdApi.Function<*>) -> TdApi.Object?) {
        every { client.send(any<TdApi.Function<TdApi.Object>>(), any<GenericResultHandler<TdApi.Object>>()) } answers {
            val result = respond(firstArg())
            if (result != null) secondArg<GenericResultHandler<TdApi.Object>>().onResult(TdResult.of(result))
        }
    }

    private fun pending(chatId: Long, id: Long) = TdApi.Message().apply {
        this.chatId = chatId
        this.id = id
        sendingState = TdApi.MessageSendingStatePending()
    }

    private fun <T> timed(block: () -> T): Pair<Result<T>, Long> {
        val start = System.nanoTime()
        val result = runCatching(block)
        return result to TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
    }

    @Test
    fun `an unanswered read ends at the deadline, is retryable, and gates its scope briefly`() {
        answer { null }
        val service = service()

        val (result, elapsed) = timed { ToolCallDeadline.run(Duration.ofMillis(300)) { service.getMe() } }
        val error = result.exceptionOrNull() as TelegramTimeoutException
        assertEquals(TelegramTimeoutException.Reason.TOOL_DEADLINE, error.reason)
        assertTrue(error.requestSent)
        assertTrue(error.retryable)
        assertTrue(elapsed < 2_000, "returned after $elapsed ms, not after the 40 s request timeout")
        assertEquals(ToolError.TELEGRAM_TIMEOUT, ToolError.from(error).code)

        val gated = assertFailsWith<TelegramFloodGateException> { service.getMe() }
        assertTrue(gated.afterLocalTimeout)
        assertEquals("method GetMe", gated.scope)
        assertEquals(0, breaker.metrics.numberOfFailedCalls, "a deadline cut is not a health failure")
    }

    @Test
    fun `the worst-case send chain fits inside the deadline and reports an unknown outcome`() {
        // SendMessage is answered with a provisional message only after a delay,
        // the final state never arrives, and the title lookup never answers.
        answer { request ->
            when (request) {
                is TdApi.SendMessage -> { Thread.sleep(200); pending(9, 101) }
                else -> null
            }
        }
        val service = service()
        val deadline = Duration.ofMillis(600)

        val (result, elapsed) = timed {
            ToolCallDeadline.run(deadline) { service.sendMessage(9, "hi", ParseMode.PLAIN, null, null) }
        }
        val error = result.exceptionOrNull() as SendOutcomeUnknownException
        assertTrue(error.deadlineExceeded)
        assertTrue(elapsed < deadline.toMillis() + 500, "chain took $elapsed ms for a ${deadline.toMillis()} ms deadline")
        val toolError = ToolError.from(error)
        assertEquals("SEND_OUTCOME_UNKNOWN", toolError.code)
        assertFalse(toolError.retryable)
        assertTrue("get_send_operation" in toolError.next_action)

        // Every kind of send to that chat is gated for a short while.
        val gated = assertFailsWith<TelegramFloodGateException> { service.sendMessage(9, "again", ParseMode.PLAIN, null, null) }
        assertEquals("send chat 9", gated.scope)
        assertTrue(gated.retryAfterSeconds <= 10)
        assertEquals(0, breaker.metrics.numberOfFailedCalls)
    }

    @Test
    fun `a rate-limiter wait longer than the deadline is not taken`() {
        answer { TdApi.Ok() }
        // One permit per 5 s; the limiter itself would wait up to 10 s.
        val service = service(rateLimiter = limiter(perPeriod = 1, period = Duration.ofSeconds(5), timeout = Duration.ofSeconds(10)))
        service.leaveChat(1)

        val (result, elapsed) = timed { ToolCallDeadline.run(Duration.ofMillis(300)) { service.leaveChat(2) } }
        val error = result.exceptionOrNull() as TelegramTimeoutException
        assertFalse(error.requestSent)
        assertTrue(error.retryable, "nothing was sent")
        assertTrue(elapsed < 1_000, "did not sit out the limiter's wait ($elapsed ms)")
    }

    @Test
    fun `a full request timeout outside a deadline still counts for the breaker`() {
        answer { null }
        val service = service(TdLibProperties.Requests(timeout = Duration.ofMillis(100), localTimeoutGate = Duration.ZERO))

        val error = assertFailsWith<TelegramTimeoutException> { service.getMe() }
        assertEquals(TelegramTimeoutException.Reason.REQUEST_TIMEOUT, error.reason)
        assertEquals(1, breaker.metrics.numberOfFailedCalls)
    }

    @Test
    fun `a sent read that timed out advertises the remaining local gate as its retry delay`() {
        answer { null }
        val service = service(TdLibProperties.Requests(timeout = Duration.ofMillis(100), localTimeoutGate = Duration.ofSeconds(3)))

        val error = assertFailsWith<TelegramTimeoutException> { service.getMe() }
        assertTrue(error.requestSent && error.retryable)
        assertEquals(3L, error.retryAfterSeconds)
        assertEquals(3L, ToolError.from(error).retry_after_seconds)
        val gated = assertFailsWith<TelegramFloodGateException> { service.getMe() }
        assertTrue(gated.retryAfterSeconds <= 3, "a retry after the advertised delay is past the gate")

        val ungated = service(TdLibProperties.Requests(timeout = Duration.ofMillis(100), localTimeoutGate = Duration.ZERO))
        val noGate = assertFailsWith<TelegramTimeoutException> { ungated.leaveChat(5) }
        assertFalse(noGate.retryable, "a write is never retryable")
        assertEquals(null, ToolError.from(noGate).retry_after_seconds)
        assertEquals(1L, noGate.retryAfterSeconds, "without a local gate the hint falls back to 1 s")
    }

    @Test
    fun `download timeouts use their own timeout and do not open the breaker`() {
        answer { request ->
            when (request) {
                is TdApi.GetMessages -> TdApi.Messages(1, arrayOf(TdApi.Message().apply {
                    chatId = 1
                    id = 2
                    content = TdApi.MessageDocument(
                        TdApi.Document("a.bin", "application/octet-stream", null, null, TdApi.File().apply { id = 7 }),
                        TdApi.FormattedText("", emptyArray()),
                    )
                }))
                else -> null // DownloadFile never finishes
            }
        }
        val service = service(
            TdLibProperties.Requests(timeout = Duration.ofSeconds(5), downloadTimeout = Duration.ofMillis(100), localTimeoutGate = Duration.ZERO),
        )

        repeat(10) {
            val error = assertFailsWith<TelegramTimeoutException> { service.downloadMedia(1, 2) }
            assertEquals(TelegramTimeoutException.Reason.DOWNLOAD_TIMEOUT, error.reason)
            assertTrue(error.retryable)
        }
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state)
        assertEquals(0, breaker.metrics.numberOfFailedCalls)
    }

    @Test
    fun `a full account bulkhead fails fast with a retryable busy error`() {
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        answer { request ->
            if (request is TdApi.GetMe) { entered.countDown(); release.await(5, TimeUnit.SECONDS) }
            TdApi.Ok()
        }
        val service = service(TdLibProperties.Requests(maxConcurrentCalls = 1))
        val pool = Executors.newSingleThreadExecutor()
        try {
            val blocked = pool.submit { runCatching { service.getMe() } }
            assertTrue(entered.await(5, TimeUnit.SECONDS))

            val (result, elapsed) = timed { service.leaveChat(1) }
            val busy = result.exceptionOrNull() as TelegramBusyException
            assertTrue(elapsed < 500, "no queueing behind the bulkhead ($elapsed ms)")
            val error = ToolError.from(busy)
            assertEquals(ToolError.TELEGRAM_BUSY, error.code)
            assertTrue(error.retryable)
            assertEquals(1L, error.retry_after_seconds)

            release.countDown()
            blocked.get(5, TimeUnit.SECONDS)
            assertTrue(service.leaveChat(1), "the permit is released after the call")
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }
    @Test
    fun `a send holds its bulkhead permit while it waits for the final message state`() {
        val accepted = CountDownLatch(1)
        answer { request ->
            when (request) {
                is TdApi.SendMessage -> { accepted.countDown(); pending(9, 101) }
                is TdApi.LeaveChat -> TdApi.Ok()
                else -> null
            }
        }
        val service = service(TdLibProperties.Requests(maxConcurrentCalls = 1, messageSendTimeout = Duration.ofMillis(800)))
        val pool = Executors.newSingleThreadExecutor()
        try {
            // TDLib accepted the send; its final state never arrives.
            val sending = pool.submit<Result<*>> { runCatching { service.sendMessage(9, "hi", ParseMode.PLAIN, null, null) } }
            assertTrue(accepted.await(5, TimeUnit.SECONDS))
            Thread.sleep(100)

            val busy = assertFailsWith<TelegramBusyException> { service.leaveChat(1) }
            assertTrue(busy.retryable)

            assertTrue(sending.get(5, TimeUnit.SECONDS).exceptionOrNull() is SendOutcomeUnknownException)
            assertTrue(service.leaveChat(1), "the permit is released once the final-state wait ends")
        } finally {
            pool.shutdownNow()
        }
    }

    /** Forwards 11, 12, 13 to chat 9: the first is delivered, the others never reach a final state. */
    private fun forwardFirstOnly() = answer { request ->
        when (request) {
            is TdApi.ForwardMessages -> {
                val provisional = (0 until 3).map { pending(9, 101L + it) }
                tracker.onSucceeded(TdApi.UpdateMessageSendSucceeded(TdApi.Message().apply { chatId = 9; id = 201 }, 101))
                TdApi.Messages(3, provisional.toTypedArray())
            }
            else -> null
        }
    }

    @Test
    fun `a forward that stops without a final state after a delivery reports the partial delivery`() {
        forwardFirstOnly()
        val service = service(TdLibProperties.Requests(messageSendTimeout = Duration.ofMillis(200)))

        val partial = assertFailsWith<ForwardPartiallyDeliveredException> { service.forwardMessages(5, 9, listOf(11, 12, 13)) }
        assertTrue(partial.cause is SendOutcomeUnknownException)
        assertFalse(partial.floodWait)
        assertEquals(listOf(11L), partial.deliveredMessageIds)
        assertEquals(listOf(12L, 13L), partial.unconfirmedMessageIds)
        val error = ToolError.from(partial)
        assertEquals(ToolError.PARTIAL_DELIVERY, error.code)
        assertFalse(error.retryable)
        assertEquals(null, error.retry_after_seconds)
        assertEquals(listOf(11L), error.delivered_message_ids)
        assertEquals(listOf(12L, 13L), error.unconfirmed_message_ids)
        assertTrue("Do not repeat the same call" in error.next_action)
        assertTrue("did not arrive in time" in error.next_action)
    }

    @Test
    fun `a forward cut by the tool deadline after a delivery reports the partial delivery`() {
        forwardFirstOnly()
        val service = service()

        val (result, elapsed) = timed {
            ToolCallDeadline.run(Duration.ofMillis(400)) { service.forwardMessages(5, 9, listOf(11, 12, 13)) }
        }
        val partial = result.exceptionOrNull() as ForwardPartiallyDeliveredException
        assertTrue((partial.cause as SendOutcomeUnknownException).deadlineExceeded)
        assertEquals(listOf(11L), partial.deliveredMessageIds)
        assertEquals(listOf(12L, 13L), partial.unconfirmedMessageIds)
        assertTrue(elapsed < 2_000, "stopped at the deadline ($elapsed ms)")
    }

    @Test
    fun `a forward with nothing delivered still reports one unknown outcome`() {
        answer { request ->
            when (request) {
                is TdApi.ForwardMessages -> TdApi.Messages(2, arrayOf(pending(9, 101), pending(9, 102)))
                else -> null
            }
        }
        val service = service(TdLibProperties.Requests(messageSendTimeout = Duration.ofMillis(200)))
        assertFailsWith<SendOutcomeUnknownException> { service.forwardMessages(5, 9, listOf(11, 12)) }
    }
}
