package dev.telegrammcp.server.config

import io.modelcontextprotocol.spec.McpSchema
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal fun assertToolError(code: String, call: () -> McpSchema.CallToolResult) {
    val result = call()
    assertTrue(result.isError)
    val data = (result.structuredContent() as Map<*, *>)["data"] as Map<*, *>
    assertEquals(code, (data["error"] as Map<*, *>)["code"])
}
