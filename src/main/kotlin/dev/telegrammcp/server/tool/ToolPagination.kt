package dev.telegrammcp.server.tool

import dev.telegrammcp.server.service.OperationGuardService
import io.modelcontextprotocol.spec.McpSchema

/** Uniform metadata for list responses; a short or filtered list is not proof of exhaustion. */
object ToolPagination {
    val schema: Map<String, Any> = WireSchemas.obj(
        "returned_count" to (WireSchemas.integer + ("minimum" to 0)),
        "complete" to WireSchemas.nil,
        "continuation_parameters" to WireSchemas.array(WireSchemas.string),
        "scope" to (WireSchemas.string + ("const" to "returned_items")),
    )

    fun decorate(tool: McpSchema.Tool, result: McpSchema.CallToolResult): McpSchema.CallToolResult {
        if (result.isError == true || tool.name() in OperationGuardService.WRITE_TOOLS) return result
        val envelope = result.structuredContent() as? Map<*, *> ?: return result
        val list = envelope["data"] as? List<*> ?: return result
        @Suppress("UNCHECKED_CAST")
        val properties = tool.inputSchema()["properties"] as? Map<String, Any> ?: emptyMap()
        val meta = envelope["meta"] as? Map<*, *> ?: return result
        val page = mapOf("returned_count" to list.size, "complete" to null, "scope" to "returned_items",
            "continuation_parameters" to listOf("offset", "from_message_id").filter { it in properties })
        val builder = McpSchema.CallToolResult.builder().isError(false).meta(result.meta())
            .structuredContent(mapOf("data" to list, "meta" to (meta + ("page" to page))))
        result.content().forEach { builder.addContent(it) }
        return builder.build()
    }
}
