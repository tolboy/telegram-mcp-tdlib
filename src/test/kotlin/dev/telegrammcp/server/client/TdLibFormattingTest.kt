package dev.telegrammcp.server.client

import dev.telegrammcp.server.config.ResilienceConfig
import dev.telegrammcp.server.exception.TelegramApiException
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
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.service.SendOperationJournal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class TdLibFormattingTest {
    @TempDir lateinit var root: Path
    private val client = mockk<SimpleTelegramClient>()
    private val requests = mutableListOf<TdApi.Function<*>>()
    private val service = TdLibClientService(
        client, RateLimiter.ofDefaults("formatting"),
        CircuitBreaker.of("formatting", ResilienceConfig.ignoringNonHealthFailures(CircuitBreakerConfig.ofDefaults())),
        SimpleMeterRegistry(),
    )

    private fun answer(respond: (TdApi.Function<*>) -> TdApi.Object) {
        every { client.send(any<TdApi.Function<TdApi.Object>>(), any<GenericResultHandler<TdApi.Object>>()) } answers {
            val request = firstArg<TdApi.Function<*>>()
            requests += request
            secondArg<GenericResultHandler<TdApi.Object>>().onResult(TdResult.of(respond(request)))
        }
    }

    @Test fun `invalid formatting cannot send reply edit or schedule raw markup`() {
        answer { TdApi.Error(400, "Can't parse entities: unclosed tag") }
        val writes = listOf<() -> Any>(
            { service.sendMessage(9, "<b>broken", ParseMode.HTML, null, null) },
            { service.replyToMessage(9, 1, "<b>broken", ParseMode.HTML) },
            { service.editMessage(9, 1, "<b>broken", ParseMode.HTML) },
            { service.scheduleMessage(9, "<b>broken", (java.time.Instant.now().epochSecond + 300).toInt(), parseMode = ParseMode.HTML) },
        )
        writes.forEach { write ->
            val failure = assertFailsWith<TelegramApiException> { ToolCallDeadline.run { write() } }
            assertEquals("INVALID_INPUT", ToolError.from(failure).code)
        }
        assertEquals(4, requests.size)
        requests.forEach { assertEquals("ParseTextEntities", it.javaClass.simpleName) }
    }

    @Test fun `parser flood retains backoff and never dispatches a send`() {
        answer { TdApi.Error(429, "Too Many Requests: retry after 17") }
        val failure = assertFailsWith<TelegramApiException> {
            ToolCallDeadline.run { service.sendMessage(9, "**bold**", ParseMode.MARKDOWN, null, null) }
        }
        assertEquals("TELEGRAM_FLOOD_WAIT", ToolError.from(failure).code)
        assertEquals(17L, ToolError.from(failure).retry_after_seconds)
        assertEquals(listOf("ParseTextEntities"), requests.map { it.javaClass.simpleName })
    }

    @Test fun `keyed invalid markup is REJECTED and cannot create an unknown delivery`() {
        answer { TdApi.Error(400, "Can't parse entities: unclosed tag") }
        val journal = SendOperationJournal(root, jacksonObjectMapper())
        repeat(2) {
            assertFailsWith<TelegramApiException> {
                ToolCallDeadline.run {
                    journal.send("default", "bad-format", 9, "same-request", {}) {
                        service.sendMessage(9, "<b>broken", ParseMode.HTML, null, null).messageId
                    }
                }
            }
            assertEquals("REJECTED", journal.status("default", "bad-format", 9)["status"])
        }
        assertEquals(listOf("ParseTextEntities", "ParseTextEntities"), requests.map { it.javaClass.simpleName })
    }

    @Test fun `parser rejection after an earlier write keeps the keyed outcome unknown`() {
        answer { TdApi.Error(400, "Can't parse entities: unclosed tag") }
        val journal = SendOperationJournal(root, jacksonObjectMapper())
        assertFailsWith<TelegramApiException> {
            ToolCallDeadline.run {
                journal.send("default", "after-write", 9, "same-request", {}) {
                    ToolCallDeadline.markWriteDispatched()
                    service.sendMessage(9, "<b>broken", ParseMode.HTML, null, null).messageId
                }
            }
        }
        assertEquals("UNKNOWN", journal.status("default", "after-write", 9)["status"])
    }

    @Test fun `valid formatting preserves the parsed entities and plain text skips parsing`() {
        val formatted = TdApi.FormattedText("bold", arrayOf(TdApi.TextEntity(0, 4, TdApi.TextEntityTypeBold())))
        answer { request ->
            when (request) {
                is TdApi.ParseTextEntities -> formatted
                is TdApi.SendMessage -> TdApi.Message().apply {
                    id = 100; chatId = 9; date = 1
                    content = TdApi.MessageText().apply { text = (request.inputMessageContent as TdApi.InputMessageText).text }
                }
                is TdApi.GetChat -> TdApi.Chat().apply { id = 9; title = "Test" }
                else -> TdApi.Ok()
            }
        }
        service.sendMessage(9, "<b>bold</b>", ParseMode.HTML, null, null)
        assertSame(formatted, (requests.filterIsInstance<TdApi.SendMessage>().single().inputMessageContent as TdApi.InputMessageText).text)
        requests.clear()
        service.sendMessage(9, "<b>literal</b>", ParseMode.PLAIN, null, null)
        assertEquals(0, requests.filterIsInstance<TdApi.ParseTextEntities>().size)
        assertEquals("<b>literal</b>", (requests.filterIsInstance<TdApi.SendMessage>().single().inputMessageContent as TdApi.InputMessageText).text.text)
    }
}
