package dev.telegrammcp.server.tool

import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import kotlin.test.assertTrue

private val outputValidator = DefaultJsonSchemaValidator()

/** Validate actual handler output, including errors, using the client's SDK validator. */
fun McpToolHandler.executeChecked(exchange: McpSyncServerExchange, arguments: Map<String, Any>): McpSchema.CallToolResult {
    val result = execute(exchange, arguments)
    val validation = outputValidator.validate(definition().outputSchema(), result.structuredContent())
    assertTrue(validation.valid(), "${definition().name()}: ${validation.errorMessage()}")
    return result
}
