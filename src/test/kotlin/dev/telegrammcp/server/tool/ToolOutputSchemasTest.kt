package dev.telegrammcp.server.tool

import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolOutputSchemasTest {
    private val validator = DefaultJsonSchemaValidator()

    @Test
    fun `schemas are valid and keep the shared envelope`() {
        listOf(ToolOutputSchemas.receipt, ToolOutputSchemas.sendResult, ToolOutputSchemas.export, ToolOutputSchemas.search).forEach { data ->
            val tool = ToolSupport.definition("test", "test", mapOf("type" to "object"), data)
            val schema = tool.outputSchema()
            val checked = validator.validateSchema(schema)
            assertTrue(checked.valid(), checked.errorMessage())
            assertFalse(validator.validate(schema, emptyMap<String, Any>()).valid())
            val error = ToolSupport.errorResult(IllegalStateException("unavailable"))
            val result = validator.validate(schema, error.structuredContent())
            assertTrue(result.valid(), result.errorMessage())
        }
    }

    @Test
    fun `receipt rejects mismatched status id pairs wrong types and unknown fields`() {
        val receipt = mapOf("operation_id" to "a".repeat(64), "chat_id" to 42L, "status" to "SENT", "message_id" to 100L)
        assertTrue(validator.validate(ToolOutputSchemas.receipt, receipt).valid())
        assertFalse(validator.validate(ToolOutputSchemas.receipt, receipt - "operation_id").valid())
        assertFalse(validator.validate(ToolOutputSchemas.receipt, receipt + ("message_id" to "100")).valid())
        assertFalse(validator.validate(ToolOutputSchemas.receipt, receipt + ("message_id" to null)).valid())
        assertFalse(validator.validate(ToolOutputSchemas.receipt, receipt + ("status" to "UNKNOWN")).valid())
        assertFalse(validator.validate(ToolOutputSchemas.receipt, receipt + ("extra" to true)).valid())
        assertTrue(validator.validate(ToolOutputSchemas.receipt,
            receipt + mapOf("status" to "UNKNOWN", "message_id" to null)).valid())
    }

    @Test
    fun `message requires identifiers and preserves numeric or textual dates`() {
        val message = mapOf("messageId" to 1, "chatId" to 42, "chatTitle" to null,
            "senderName" to "Alice", "text" to null, "date" to 1234.5)
        assertTrue(validator.validate(ToolOutputSchemas.message, message).valid())
        assertTrue(validator.validate(ToolOutputSchemas.message, message + ("date" to "2026-09-24T00:00:00Z")).valid())
        assertFalse(validator.validate(ToolOutputSchemas.message, message - "chatId").valid())
        assertFalse(validator.validate(ToolOutputSchemas.message, message + ("text" to 42)).valid())
    }
}
