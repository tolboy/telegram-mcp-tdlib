package dev.telegrammcp.server.tool

import dev.telegrammcp.server.exception.AntiSpamException
import dev.telegrammcp.server.exception.ChatNotAllowedException
import dev.telegrammcp.server.exception.ApprovalDeniedException
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
}
