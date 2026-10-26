package dev.telegrammcp.server.exception

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Error strings below are real TDLib 1.8.68 / Telegram server messages. */
class TelegramApiExceptionTest {

    private fun kind(code: Int, message: String) = TelegramErrorClassifier.classify(code, message)

    @ParameterizedTest(name = "{0} {1} -> retry after {2}")
    @CsvSource(
        // What TDLib actually returns once NetQueryDelayer gives up waiting.
        "429, 'Too Many Requests: retry after 17', 17",
        "429, 'Too Many Requests: retry after 3600', 3600",
        // Raw server forms (only seen if TDLib forwards them unchanged).
        "420, FLOOD_WAIT_17, 17",
        "420, FLOOD_PREMIUM_WAIT_5, 5",
        "420, SLOWMODE_WAIT_60, 60",
        "420, 2FA_CONFIRM_WAIT_604800, 604800",
        "420, TAKEOUT_INIT_DELAY_86400, 86400",
    )
    fun `flood waits are retryable, not executed and outside the breaker`(code: Int, message: String, seconds: Long) {
        val error = TelegramApiException(code, message)
        assertEquals(TelegramErrorKind.FLOOD_WAIT, error.kind)
        assertTrue(error.isFloodWait)
        assertTrue(error.isRetryable)
        assertTrue(error.wasNotExecuted)
        assertFalse(error.countsTowardBreaker)
        assertEquals(seconds, error.retryAfterSeconds)
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(
        "-1, Canceled",
        "406, Request canceled",
        "406, Canceled",
        "200, Canceled by another request",
        "500, Request aborted",
    )
    fun `cancellations get their own kind and do not count toward the breaker`(code: Int, message: String) {
        val error = TelegramApiException(code, message)
        assertEquals(TelegramErrorKind.CANCELLED, error.kind)
        assertFalse(error.countsTowardBreaker)
        assertFalse(error.isRetryable)
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(
        "400, Can't access the chat",
        "400, Not enough rights to send text messages to the chat",
        "400, Not enough rights to pin a message",
        "400, Have not enough rights to invite members",
        "400, CHAT_ADMIN_REQUIRED",
        "400, RIGHT_FORBIDDEN",
        "400, CHAT_WRITE_FORBIDDEN",
        "400, CHAT_SEND_PHOTOS_FORBIDDEN",
        "400, CHANNEL_PRIVATE",
        "400, USER_PRIVACY_RESTRICTED",
        "403, USER_BANNED_IN_CHANNEL",
        "400, Have no access to the chat",
        "400, Have no write access to the chat",
        "400, Have no access to the user",
        "400, Chat is not accessible",
        "400, User is inaccessible",
        "400, PEER_FLOOD",
    )
    fun `permission failures are PERMISSION_DENIED`(code: Int, message: String) {
        assertEquals(TelegramErrorKind.PERMISSION_DENIED, kind(code, message))
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(
        "400, Chat not found",
        "400, Message not found",
        "400, Invalid chat identifier specified",
        "400, Wrong message identifier",
        "400, Can't find secret chat",
        "400, MSG_ID_INVALID",
        "400, PEER_ID_INVALID",
        "400, USERNAME_NOT_OCCUPIED",
        "400, Invalid user identifier",
        "404, Not Found",
        // Fixed 500 outcomes TDLib produces locally.
        "500, Topic not found",
        "500, Sticker set not found",
        "500, Chat not found",
    )
    fun `missing objects are NOT_FOUND`(code: Int, message: String) {
        val error = TelegramApiException(code, message)
        assertEquals(TelegramErrorKind.NOT_FOUND, error.kind)
        assertFalse(error.countsTowardBreaker)
        assertFalse(error.isRetryable)
    }

    @Test
    fun `permission strings win over not-found and local lookups are not NOT_FOUND`() {
        // "Can't access the chat" is TDLib's answer for both unknown and inaccessible chats.
        assertEquals(TelegramErrorKind.PERMISSION_DENIED, kind(400, "Can't access the chat"))
        assertEquals(TelegramErrorKind.INVALID_INPUT, kind(400, "File not found"))
        assertEquals(TelegramErrorKind.INVALID_INPUT, kind(400, "Chat list not found"))
        assertEquals(TelegramErrorKind.INVALID_INPUT, kind(400, "MESSAGE_TOO_LONG"))
        assertEquals(TelegramErrorKind.INVALID_INPUT, kind(500, "Unknown parse mode"))
        assertEquals(TelegramErrorKind.INVALID_INPUT, kind(500, "Can't find suitable file name"))
    }

    @Test
    fun `only allow-listed transient 5xx failures are retryable and only for reads`() {
        listOf(
            500 to "WORKER_BUSY_TOO_LONG_RETRY",
            500 to "Timeout expired",
            502 to "Bad Gateway",
            503 to "Service Unavailable",
        ).forEach { (code, message) ->
            val read = TelegramApiException(code, message, readOnlyRequest = true)
            val write = TelegramApiException(code, message)
            assertEquals(TelegramErrorKind.TRANSIENT_SERVER, read.kind, message)
            assertTrue(read.isRetryable, message)
            assertFalse(write.isRetryable, "writes may have taken effect: $message")
            assertTrue(read.countsTowardBreaker && write.countsTowardBreaker, message)
        }
    }

    @Test
    fun `other 5xx failures count toward the breaker but are never retryable`() {
        // Can follow a write that Telegram executed.
        val invalid = TelegramApiException(500, "Receive invalid response", readOnlyRequest = true)
        assertEquals(TelegramErrorKind.SERVER_ERROR, invalid.kind)
        assertTrue(invalid.countsTowardBreaker)
        assertFalse(invalid.isRetryable)
        assertFalse(invalid.wasNotExecuted)
    }

    @Test
    fun `auth and unknown codes`() {
        assertEquals(TelegramErrorKind.AUTH_REQUIRED, kind(401, "Unauthorized"))
        assertEquals(TelegramErrorKind.UNKNOWN, kind(0, "something"))
        assertFalse(TelegramApiException(401, "Unauthorized").countsTowardBreaker)
    }

    @Test
    fun `retry delay parsing`() {
        assertEquals(30L, TelegramApiException.parseRetryAfterSeconds("Too Many Requests: retry after 30"))
        assertEquals(12L, TelegramApiException.parseRetryAfterSeconds("SLOWMODE_WAIT_12"))
        assertNull(TelegramApiException.parseRetryAfterSeconds("FLOOD_WAIT_"))
        assertNull(TelegramApiException.parseRetryAfterSeconds("Chat not found"))
        assertNull(TelegramApiException(429, "Too Many Requests").retryAfterSeconds)
        assertEquals(1L, TelegramApiException.parseRetryAfterSeconds("Too Many Requests: retry after 0"), "0 is reported as 1")
        assertEquals(1L, TelegramApiException.parseRetryAfterSeconds("FLOOD_WAIT_0"))
    }

    @Test
    fun `PEER_FLOOD is a spam restriction, not a flood wait`() {
        val error = TelegramApiException(400, "PEER_FLOOD")
        assertEquals(TelegramErrorKind.PERMISSION_DENIED, error.kind)
        assertFalse(error.isFloodWait)
        assertFalse(error.wasNotExecuted)
        assertFalse(error.isRetryable)
        assertFalse(error.countsTowardBreaker)
        assertNull(error.retryAfterSeconds)
        // Whatever code Telegram uses, it is never treated as a timed flood wait.
        assertEquals(TelegramErrorKind.PERMISSION_DENIED, TelegramApiException(420, "PEER_FLOOD").kind)
    }
}
