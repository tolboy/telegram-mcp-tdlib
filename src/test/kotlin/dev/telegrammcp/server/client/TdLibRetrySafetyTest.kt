package dev.telegrammcp.server.client

import dev.telegrammcp.server.config.ResilienceConfig
import dev.telegrammcp.server.exception.TelegramApiException
import dev.telegrammcp.server.exception.TelegramFloodGateException
import dev.telegrammcp.server.model.ParseMode
import dev.telegrammcp.server.tool.ToolError
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.ratelimiter.RateLimiter
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import it.tdlight.client.GenericResultHandler
import it.tdlight.client.Result as TdResult
import it.tdlight.client.SimpleTelegramClient
import it.tdlight.jni.TdApi
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TdLibRetrySafetyTest {
    private val client = mockk<SimpleTelegramClient>()
    private val gate = TelegramFloodGate()
    private val tracker = MessageSendTracker()
    private val service = TdLibClientService(
        client, RateLimiter.ofDefaults("retry-safety"),
        CircuitBreaker.of("retry-safety", ResilienceConfig.ignoringNonHealthFailures(CircuitBreakerConfig.ofDefaults())),
        SimpleMeterRegistry(), messageSendTracker = tracker, floodGate = gate,
    )

    private fun answer(respond: (TdApi.Function<*>) -> TdApi.Object) {
        every { client.send(any<TdApi.Function<TdApi.Object>>(), any<GenericResultHandler<TdApi.Object>>()) } answers {
            secondArg<GenericResultHandler<TdApi.Object>>().onResult(TdResult.of(respond(firstArg())))
        }
    }

    private fun chat() = TdApi.Chat().apply {
        id = -100; title = "created"
        type = TdApi.ChatTypeSupergroup().apply { supergroupId = 42; isChannel = false }
    }

    private fun forumFailure(gated: Boolean): Exception {
        var creations = 0
        answer { request ->
            when (request) {
                is TdApi.CreateNewSupergroupChat -> { creations++; chat() }
                is TdApi.GetChat -> chat()
                is TdApi.ToggleSupergroupIsForum -> TdApi.Error(429, "Too Many Requests: retry after 7")
                else -> TdApi.Ok()
            }
        }
        if (gated) gate.record("method ToggleSupergroupIsForum", TelegramApiException(429, "retry after 7"))
        val failure = ToolCallDeadline.run {
            runCatching { service.createSupergroupOrChannel("created", "", true, true) }.exceptionOrNull() as Exception
        }
        // Mapping happens after the invocation state was removed: the exception must retain the evidence.
        val error = ToolError.from(failure)
        assertFalse(error.retryable, "group creation already succeeded")
        assertNull(error.retry_after_seconds)
        assertTrue("Do not repeat the unchanged call" in error.next_action)
        assertEquals(1, creations)
        return failure
    }

    @Test
    fun `a forum flood cannot advertise recreating the already created group`() {
        assertTrue(forumFailure(gated = false) is TelegramApiException)
    }

    @Test
    fun `a forum gate rejection cannot advertise recreating the already created group`() {
        assertTrue(forumFailure(gated = true) is TelegramFloodGateException)
    }

    @Test
    fun `a transient read failure after a write is not a retryable call`() {
        answer { if (it is TdApi.GetMe) TdApi.Error(503, "Unavailable") else TdApi.Ok() }
        val failure = ToolCallDeadline.run {
            service.leaveChat(9)
            assertFailsWith<TelegramApiException> { service.getMe() }
        }
        assertTrue(failure.readOnlyRequest)
        assertFalse(ToolError.from(failure).retryable)
    }

    @Test
    fun `a single send rejected asynchronously still permits a safe retry`() {
        answer { request ->
            if (request is TdApi.SendMessage) {
                val pending = TdApi.Message().apply { chatId = 9; id = 101; sendingState = TdApi.MessageSendingStatePending() }
                tracker.onFailed(TdApi.UpdateMessageSendFailed(pending, pending.id, TdApi.Error(429, "retry after 7")))
                pending
            } else TdApi.Ok()
        }
        val failure = ToolCallDeadline.run {
            assertFailsWith<TelegramApiException> { service.sendMessage(9, "hi", ParseMode.PLAIN, null, null) }
        }
        assertTrue(ToolError.from(failure).retryable, "the rejected send itself is not an earlier write")
        assertEquals(7L, ToolError.from(failure).retry_after_seconds)
    }
}
