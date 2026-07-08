package dev.telegrammcp.server.service

import dev.telegrammcp.server.config.ServerModeProperties
import dev.telegrammcp.server.model.AuditCategory
import dev.telegrammcp.server.model.AuditOutcome
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuditServiceTest {

    private lateinit var meterRegistry: SimpleMeterRegistry

    @BeforeEach
    fun setUp() {
        meterRegistry = SimpleMeterRegistry()
    }

    private fun auditService(
        enabled: Boolean = true,
        logArguments: Boolean = false,
    ): AuditService {
        return AuditService(
            props = ServerModeProperties(
                audit = ServerModeProperties.AuditProps(
                    enabled = enabled,
                    logArguments = logArguments,
                ),
            ),
            meterRegistry = meterRegistry,
        )
    }

    @Test
    fun `records audit entry on success`() {
        val service = auditService()

        service.record(
            toolName = "send_message",
            arguments = mapOf("chat_id" to 42, "text" to "hello"),
            outcome = AuditOutcome.SUCCESS,
            durationMs = 100,
        )

        val entries = service.getRecentEntries()
        assertEquals(1, entries.size)
        assertEquals("send_message", entries[0].toolName)
        assertEquals(AuditCategory.SEND_MESSAGE, entries[0].category)
        assertEquals(AuditOutcome.SUCCESS, entries[0].outcome)
        assertEquals(100L, entries[0].durationMs)
    }

    @Test
    fun `records audit entry on error`() {
        val service = auditService()

        service.record(
            toolName = "get_history",
            outcome = AuditOutcome.ERROR,
            error = "Chat not found",
        )

        val entries = service.getRecentEntries()
        assertEquals(1, entries.size)
        assertEquals(AuditOutcome.ERROR, entries[0].outcome)
        assertEquals("Chat not found", entries[0].errorMessage)
    }

    @Test
    fun `does not record when disabled`() {
        val service = auditService(enabled = false)

        service.record("send_message")

        val entries = service.getRecentEntries()
        assertTrue(entries.isEmpty())
    }

    @Test
    fun `redacts sensitive arguments when logArguments is false`() {
        val service = auditService(logArguments = false)

        service.record(
            toolName = "send_message",
            arguments = mapOf("chat_id" to 42, "text" to "secret stuff", "token" to "abc123"),
        )

        val entries = service.getRecentEntries()
        // Only non-sensitive keys (chat_id, limit, offset, query) are kept
        assertTrue(entries[0].arguments.containsKey("chat_id"))
        assertTrue("text" !in entries[0].arguments)
        assertTrue("token" !in entries[0].arguments)
    }

    @Test
    fun `redacts password fields when logArguments is true`() {
        val service = auditService(logArguments = true)

        service.record(
            toolName = "send_message",
            arguments = mapOf("chat_id" to 42, "password" to "secret123"),
        )

        val entries = service.getRecentEntries()
        assertEquals("***REDACTED***", entries[0].arguments["password"])
    }

    @Test
    fun `redacts sensitive keys nested in object and array arguments`() {
        val service = auditService(logArguments = true)

        service.record(
            toolName = "send_message",
            arguments = mapOf(
                "chat_id" to 42,
                "options" to mapOf(
                    "bot_token" to "123:abc",
                    "proxyPassword" to "hunter2",
                    "label" to "kept",
                ),
                "recipients" to listOf(
                    mapOf("phone_number" to "+15551234567", "name" to "kept-too"),
                ),
            ),
        )

        val entry = service.getRecentEntries().first()
        @Suppress("UNCHECKED_CAST")
        val options = entry.arguments["options"] as Map<String, Any>
        assertEquals("***REDACTED***", options["bot_token"])
        assertEquals("***REDACTED***", options["proxyPassword"])
        assertEquals("kept", options["label"])

        @Suppress("UNCHECKED_CAST")
        val recipient = (entry.arguments["recipients"] as List<Map<String, Any>>).first()
        assertEquals("***REDACTED***", recipient["phone_number"])
        assertEquals("kept-too", recipient["name"])
    }

    @Test
    fun `increments metrics counter`() {
        val service = auditService()

        service.record("send_message", outcome = AuditOutcome.SUCCESS)
        service.record("send_message", outcome = AuditOutcome.SUCCESS)
        service.record("get_history", outcome = AuditOutcome.ERROR, error = "fail")

        val sendCounter = meterRegistry.counter(
            "mcp.audit.operations",
            "tool", "send_message",
            "category", AuditCategory.SEND_MESSAGE.name,
            "outcome", AuditOutcome.SUCCESS.name,
        )
        assertEquals(2.0, sendCounter.count())
    }

    @Test
    fun `ring buffer limits entries to 1000`() {
        val service = auditService()

        repeat(1050) { i ->
            service.record("get_history", arguments = mapOf("i" to i))
        }

        val entries = service.getRecentEntries(2000)
        assertEquals(1000, entries.size)
    }

    @Test
    fun `newest entries come first`() {
        val service = auditService(logArguments = true)

        service.record("get_history", arguments = mapOf("chat_id" to 1))
        service.record("send_message", arguments = mapOf("chat_id" to 2))

        val entries = service.getRecentEntries()
        assertEquals("send_message", entries[0].toolName)
        assertEquals("get_history", entries[1].toolName)
    }
}
