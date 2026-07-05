package dev.telegrammcp.server.tool.media

import com.fasterxml.jackson.databind.ObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.model.AuditOutcome
import dev.telegrammcp.server.service.AuditService
import dev.telegrammcp.server.tool.McpToolHandler
import dev.telegrammcp.server.util.StructuredLogger
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import org.springframework.stereotype.Component

/**
 * MCP tool: **get_sticker_sets**
 *
 * Lists sticker sets currently installed on the account.
 */
@Component
class GetStickerSetsTool(
    private val telegramClient: TelegramClientService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {

    private val log = StructuredLogger.forClass<GetStickerSetsTool>()

    companion object {
        const val TOOL_NAME = "get_sticker_sets"
        private const val DEFAULT_LIMIT = 50

        @Suppress("JsonStandardCompliance")
        private val INPUT_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "limit": { "type": "number", "description": "Max sticker sets to return (1–200, default 50)" }
          },
          "required": []
        }
        """.trimIndent()
    }

    override fun definition(): McpSchema.Tool =
        dev.telegrammcp.server.tool.ToolSupport.definition(TOOL_NAME, "List installed sticker sets for the current account", INPUT_SCHEMA, objectMapper)

    override fun execute(
        exchange: McpSyncServerExchange,
        arguments: Map<String, Any>,
    ): McpSchema.CallToolResult {
        val sample = Timer.start(meterRegistry)

        return try {
            val limit = (arguments["limit"] as? Number)?.toInt()
                ?: arguments["limit"]?.toString()?.toIntOrNull()
                ?: DEFAULT_LIMIT

            log.withTool(TOOL_NAME).info("Listing installed sticker sets (limit={})", limit)
            val sets = telegramClient.getInstalledStickerSets(limit)
            auditService.record(TOOL_NAME, arguments, AuditOutcome.SUCCESS)

            val json = objectMapper.writeValueAsString(sets)
            dev.telegrammcp.server.tool.ToolSupport.textResult(json)
        } catch (ex: Exception) {
            log.withTool(TOOL_NAME).error("Failed to list sticker sets: {}", ex.message, ex)
            auditService.record(TOOL_NAME, arguments, AuditOutcome.ERROR, error = ex.message)
            dev.telegrammcp.server.tool.ToolSupport.errorText("Error: ${ex.message}")
        } finally {
            sample.stop(Timer.builder("mcp.tool.execution").tag("tool", TOOL_NAME).register(meterRegistry))
        }
    }
}
