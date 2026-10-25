package dev.telegrammcp.server.tool

import dev.telegrammcp.server.exception.AntiSpamException
import dev.telegrammcp.server.exception.ChatNotAllowedException
import dev.telegrammcp.server.exception.ApprovalDeniedException
import dev.telegrammcp.server.exception.TelegramApiException
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
    fun `telegram flood waits map to RATE_LIMITED with Telegram's delay for 420 and 429 in both message forms`() {
        listOf(
            TelegramApiException(420, "FLOOD_WAIT_17") to 17L,
            TelegramApiException(420, "FLOOD_PREMIUM_WAIT_4") to 4L,
            TelegramApiException(420, "Too Many Requests: retry after 9") to 9L,
            TelegramApiException(429, "Too Many Requests: retry after 42") to 42L,
            TelegramApiException(429, "FLOOD_WAIT_5") to 5L,
        ).forEach { (exception, seconds) ->
            val error = ToolError.from(exception)
            assertEquals("RATE_LIMITED", error.code, exception.message)
            assertEquals(seconds, error.retry_after_seconds, exception.message)
            assertTrue(error.retryable, "Telegram did not execute a flood-limited request")
            assertTrue(error.next_action.contains("retry_after_seconds"))
        }
    }

    @Test
    fun `telegram errors map to stable codes by error code`() {
        fun code(errorCode: Int, message: String) = ToolError.from(TelegramApiException(errorCode, message))

        assertEquals("INVALID_INPUT", code(400, "MESSAGE_TOO_LONG").code)
        assertEquals("ENTITY_NOT_FOUND", code(400, "Chat not found").code)
        assertEquals("ENTITY_NOT_FOUND", code(400, "USER_ID_INVALID").code)
        assertEquals("ENTITY_NOT_FOUND", code(400, "MESSAGE_NOT_FOUND").code)
        assertEquals("ENTITY_NOT_FOUND", code(404, "Not Found").code)
        assertEquals("AUTH_REQUIRED", code(401, "Unauthorized").code)
        assertEquals("PERMISSION_DENIED", code(403, "CHAT_WRITE_FORBIDDEN").code)
        assertEquals("TELEGRAM_UNAVAILABLE", code(500, "Request aborted").code)
        assertEquals("TELEGRAM_API_ERROR", code(406, "hidden").code)

        listOf(400 to "MESSAGE_TOO_LONG", 401 to "Unauthorized", 403 to "CHAT_WRITE_FORBIDDEN", 404 to "Not Found").forEach { (c, m) ->
            val error = code(c, m)
            assertFalse(error.retryable, "$c must not invite an unchanged retry")
            assertEquals(null, error.retry_after_seconds)
        }
        assertTrue(code(500, "Request aborted").retryable)
        assertTrue(code(403, "CHAT_WRITE_FORBIDDEN").message.contains("CHAT_WRITE_FORBIDDEN"))
    }

    @Test
    fun `telegram rate limit is rendered in the structured error envelope`() {
        val result = ToolSupport.errorResult(TelegramApiException(420, "FLOOD_WAIT_30"))
        val envelope = result.structuredContent() as Map<*, *>
        val error = (envelope["data"] as Map<*, *>)["error"] as Map<*, *>
        assertEquals("RATE_LIMITED", error["code"])
        assertEquals(30L, (error["retry_after_seconds"] as Number).toLong())
        assertEquals(true, error["retryable"])
    }
}
