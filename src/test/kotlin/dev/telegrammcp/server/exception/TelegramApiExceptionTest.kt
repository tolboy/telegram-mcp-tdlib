package dev.telegrammcp.server.exception

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TelegramApiExceptionTest {

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(
        "420, FLOOD_WAIT_17, 17",
        "420, FLOOD_PREMIUM_WAIT_5, 5",
        "420, Too Many Requests: retry after 9, 9",
        "429, Too Many Requests: retry after 42, 42",
        "429, FLOOD_WAIT_3600, 3600",
        "429, FLOOD_PREMIUM_WAIT_1, 1",
        // A flood message under an unexpected code is still a rate limit.
        "400, FLOOD_WAIT_8, 8",
    )
    fun `flood control is a rate limit with a parsed retry delay`(code: Int, message: String, seconds: Long) {
        val error = TelegramApiException(code, message)

        assertTrue(error.isRateLimit)
        assertFalse(error.isCallerError, "rate limits must not be treated as ignorable caller errors")
        assertEquals(seconds, error.retryAfterSeconds)
    }

    @Test
    fun `rate limit codes without a parsable delay keep a null delay`() {
        listOf(420, 429).forEach { code ->
            val error = TelegramApiException(code, "Too Many Requests")
            assertTrue(error.isRateLimit)
            assertNull(error.retryAfterSeconds)
        }
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(
        "400, CHAT_NOT_FOUND",
        "400, MESSAGE_TOO_LONG",
        "401, Unauthorized",
        "403, CHAT_WRITE_FORBIDDEN",
        "404, Not Found",
    )
    fun `other 4xx errors are caller errors`(code: Int, message: String) {
        val error = TelegramApiException(code, message)

        assertTrue(error.isCallerError)
        assertFalse(error.isRateLimit)
        assertFalse(error.isServerError)
        assertNull(error.retryAfterSeconds)
    }

    @Test
    fun `5xx errors are server errors, not caller errors`() {
        val error = TelegramApiException(500, "Request aborted")

        assertTrue(error.isServerError)
        assertFalse(error.isCallerError)
        assertFalse(error.isRateLimit)
        assertEquals("Telegram API error (500): Request aborted", error.message)
    }

    @Test
    fun `retry parser ignores unrelated digits`() {
        assertNull(TelegramApiException.parseRetryAfterSeconds("USER_ID_INVALID 123"))
        assertNull(TelegramApiException.parseRetryAfterSeconds("FLOOD_WAIT_"))
        assertEquals(12L, TelegramApiException.parseRetryAfterSeconds("Too many requests: Retry After 12"))
    }
}
