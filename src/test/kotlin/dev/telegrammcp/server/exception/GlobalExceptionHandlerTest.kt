package dev.telegrammcp.server.exception

import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GlobalExceptionHandlerTest {

    private val handler = GlobalExceptionHandler()

    private fun status(code: Int, message: String) =
        handler.handleTelegramApi(TelegramApiException(code, message)).statusCode.value()

    @Test
    fun `telegram errors map to REST statuses by kind`() {
        assertEquals(400, status(400, "Message text is empty"))
        assertEquals(403, status(400, "Not enough rights to send text messages to the chat"))
        assertEquals(403, status(403, "USER_PRIVACY_RESTRICTED"))
        assertEquals(404, status(400, "Chat not found"))
        assertEquals(404, status(400, "Message not found"))
        assertEquals(404, status(500, "Topic not found"), "a fixed TDLib 500 is a caller error")
        assertEquals(400, status(500, "Unknown parse mode"))
        assertEquals(503, status(401, "Unauthorized"))
        assertEquals(503, status(406, "Request canceled"))
        assertEquals(503, status(500, "WORKER_BUSY_TOO_LONG_RETRY"))
        assertEquals(502, status(500, "Receive invalid response"))
    }

    @Test
    fun `flood waits return 429 with Retry-After`() {
        val response = handler.handleTelegramApi(TelegramApiException(429, "Too Many Requests: retry after 42"))
        assertEquals(429, response.statusCode.value())
        assertEquals("42", response.headers.getFirst(HttpHeaders.RETRY_AFTER))
        assertEquals(42L, response.body!!.properties!!["retry_after_seconds"])
        assertEquals("FLOOD_WAIT", response.body!!.properties!!["telegram_error_kind"])

        val gated = handler.handleFloodGate(TelegramFloodGateException("chat 7", 9))
        assertEquals(429, gated.statusCode.value())
        assertEquals("9", gated.headers.getFirst(HttpHeaders.RETRY_AFTER))
    }

    @Test
    fun `non-flood errors carry no Retry-After`() {
        val response = handler.handleTelegramApi(TelegramApiException(400, "Chat not found"))
        assertNull(response.headers.getFirst(HttpHeaders.RETRY_AFTER))
        assertNull(response.body!!.properties!!["retry_after_seconds"])
    }

    @Test
    fun `a flood after an earlier write does not instruct REST clients to retry`() {
        val api = handler.handleTelegramApi(TelegramApiException(429, "retry after 7").apply { priorWriteDispatched = true })
        val gate = handler.handleFloodGate(TelegramFloodGateException("method SetBio", 7).apply { priorWriteDispatched = true })
        listOf(api, gate).forEach { response ->
            assertEquals(429, response.statusCode.value())
            assertNull(response.headers.getFirst(HttpHeaders.RETRY_AFTER))
            assertNull(response.body!!.properties?.get("retry_after_seconds"))
        }
    }

    @Test
    fun `local timeouts return 504 and a full bulkhead returns 503`() {
        val timeout = handler.handleTimeout(
            TelegramTimeoutException("GetChat", TelegramTimeoutException.Reason.REQUEST_TIMEOUT, true, true, 40_000)
                .apply { retryAfterSeconds = 10 },
        )
        assertEquals(504, timeout.statusCode.value())
        assertEquals("10", timeout.headers.getFirst(HttpHeaders.RETRY_AFTER), "the scope's remaining local gate")
        assertEquals(10L, timeout.body!!.properties!!["retry_after_seconds"])
        assertEquals("REQUEST_TIMEOUT", timeout.body!!.properties!!["timeout_reason"])

        val write = handler.handleTimeout(
            TelegramTimeoutException("PinChatMessage", TelegramTimeoutException.Reason.REQUEST_TIMEOUT, true, false, 40_000),
        )
        assertNull(write.headers.getFirst(HttpHeaders.RETRY_AFTER), "a write that may have taken effect")

        val notSent = handler.handleTimeout(
            TelegramTimeoutException("GetChat", TelegramTimeoutException.Reason.TOOL_DEADLINE, false, true, 0),
        )
        assertEquals("1", notSent.headers.getFirst(HttpHeaders.RETRY_AFTER), "nothing was sent")

        val busy = handler.handleBusy(TelegramBusyException("telegram-work", 25, retryable = true))
        assertEquals(503, busy.statusCode.value())
        assertEquals("1", busy.headers.getFirst(HttpHeaders.RETRY_AFTER))
        val busyAfterWrite = handler.handleBusy(TelegramBusyException("telegram-work", 25, retryable = false))
        assertNull(busyAfterWrite.headers.getFirst(HttpHeaders.RETRY_AFTER))
    }
}
