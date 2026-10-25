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
}
