package dev.telegrammcp.server.tool

import dev.telegrammcp.server.exception.AntiSpamException
import dev.telegrammcp.server.exception.ChatNotAllowedException
import dev.telegrammcp.server.exception.ApprovalDeniedException
import dev.telegrammcp.server.exception.TelegramApiException
import dev.telegrammcp.server.exception.TelegramFloodGateException
import io.modelcontextprotocol.spec.McpSchema
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolErrorTest {
    @Test
    fun `policy failure keeps legacy text and adds structured recovery hints`() {
        val exception = ChatNotAllowedException(42)
        val result = ToolSupport.errorResult(exception)
        assertTrue(result.isError)
        assertEquals("Error: ${exception.message}", (result.content().single() as McpSchema.TextContent).text())
        val envelope = result.structuredContent() as Map<*, *>
        val error = (envelope["data"] as Map<*, *>)["error"] as Map<*, *>
        assertEquals("CHAT_FORBIDDEN", error["code"])
        assertEquals(false, error["retryable"])
        assertTrue(error["next_action"].toString().contains("do not bypass"))
    }

    @Test
    fun `retry delay rounds up and unknown daily delays do not invite retries`() {
        val limited = ToolError.from(AntiSpamException("send_message", "window", 1001))
        assertEquals(2L, limited.retry_after_seconds)
        assertFalse(limited.retryable)
        assertFalse(ToolError.from(AntiSpamException("send_message", "daily cap")).retryable)
        assertFalse(ToolError.from(ApprovalDeniedException("delete_message", "declined")).retryable)
    }

    @Test
    fun `unknown failures never imply safe retries and normalize untrusted details`() {
        val result = ToolSupport.errorResult(IllegalStateException("failure\u202E"))
        val envelope = result.structuredContent() as Map<*, *>
        val error = (envelope["data"] as Map<*, *>)["error"] as Map<*, *>
        assertEquals("INTERNAL_ERROR", error["code"])
        assertEquals(false, error["retryable"])
        assertFalse(error["message"].toString().contains('\u202E'))
        assertTrue(error["message"].toString().contains("\\u202E"))
    }

    @Test
    fun `telegram flood waits map to TELEGRAM_FLOOD_WAIT with Telegram's delay`() {
        listOf(
            TelegramApiException(429, "Too Many Requests: retry after 42") to 42L,
            TelegramApiException(420, "FLOOD_WAIT_17") to 17L,
            TelegramApiException(420, "FLOOD_PREMIUM_WAIT_4") to 4L,
            TelegramApiException(420, "SLOWMODE_WAIT_9") to 9L,
        ).forEach { (exception, seconds) ->
            val error = ToolError.from(exception)
            assertEquals("TELEGRAM_FLOOD_WAIT", error.code, exception.message)
            assertEquals(seconds, error.retry_after_seconds, exception.message)
            assertTrue(error.retryable, "Telegram did not execute a flood-limited request")
            assertTrue(error.next_action.contains("retry_after_seconds"))
        }
        val gated = ToolError.from(TelegramFloodGateException("chat 5", 12))
        assertEquals("TELEGRAM_FLOOD_WAIT", gated.code)
        assertEquals(12L, gated.retry_after_seconds)
        assertTrue(gated.retryable)
    }

    @Test
    fun `local anti-spam limits keep RATE_LIMITED and stay non-retryable`() {
        val limited = ToolError.from(AntiSpamException("send_message", "window", 5000))
        assertEquals("RATE_LIMITED", limited.code)
        assertFalse(limited.retryable)
    }

    @Test
    fun `telegram errors map to stable codes by classified kind`() {
        fun code(errorCode: Int, message: String, read: Boolean = false) =
            ToolError.from(TelegramApiException(errorCode, message, readOnlyRequest = read))

        assertEquals("INVALID_INPUT", code(400, "MESSAGE_TOO_LONG").code)
        assertEquals("INVALID_INPUT", code(400, "File not found").code)
        assertEquals("ENTITY_NOT_FOUND", code(400, "Chat not found").code)
        assertEquals("ENTITY_NOT_FOUND", code(400, "USER_ID_INVALID").code)
        assertEquals("ENTITY_NOT_FOUND", code(400, "Wrong message identifier").code)
        assertEquals("ENTITY_NOT_FOUND", code(500, "Topic not found").code)
        assertEquals("ENTITY_NOT_FOUND", code(404, "Not Found").code)
        assertEquals("PERMISSION_DENIED", code(400, "Can't access the chat").code)
        assertEquals("PERMISSION_DENIED", code(400, "CHAT_ADMIN_REQUIRED").code)
        assertEquals("PERMISSION_DENIED", code(403, "CHAT_WRITE_FORBIDDEN").code)
        assertEquals("AUTH_REQUIRED", code(401, "Unauthorized").code)
        assertEquals("TELEGRAM_REQUEST_CANCELLED", code(500, "Request aborted").code)
        assertEquals("TELEGRAM_REQUEST_CANCELLED", code(406, "Request canceled").code)
        assertEquals("TELEGRAM_UNAVAILABLE", code(500, "WORKER_BUSY_TOO_LONG_RETRY").code)
        assertEquals("TELEGRAM_API_ERROR", code(500, "Receive invalid response").code)

        listOf(400 to "MESSAGE_TOO_LONG", 401 to "Unauthorized", 403 to "CHAT_WRITE_FORBIDDEN", 404 to "Not Found",
            500 to "Request aborted", 500 to "Receive invalid response").forEach { (c, m) ->
            val error = code(c, m, read = true)
            assertFalse(error.retryable, "$c $m must not invite an unchanged retry")
            assertEquals(null, error.retry_after_seconds)
        }
        assertTrue(code(500, "Timeout expired", read = true).retryable)
        assertFalse(code(500, "Timeout expired", read = false).retryable, "a failed write may have taken effect")
        assertTrue(code(403, "CHAT_WRITE_FORBIDDEN").message.contains("CHAT_WRITE_FORBIDDEN"))
    }

    @Test
    fun `telegram flood wait is rendered in the structured error envelope`() {
        val result = ToolSupport.errorResult(TelegramApiException(429, "Too Many Requests: retry after 30"))
        val envelope = result.structuredContent() as Map<*, *>
        val error = (envelope["data"] as Map<*, *>)["error"] as Map<*, *>
        assertEquals("TELEGRAM_FLOOD_WAIT", error["code"])
        assertEquals(30L, (error["retry_after_seconds"] as Number).toLong())
        assertEquals(true, error["retryable"])
    }
}
