package dev.telegrammcp.server.tool.message

import com.fasterxml.jackson.databind.ObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.exception.InvalidToolInputException
import dev.telegrammcp.server.service.AuditService
import dev.telegrammcp.server.service.GuardrailService
import dev.telegrammcp.server.tool.McpToolHandler
import dev.telegrammcp.server.tool.ToolSupport
import dev.telegrammcp.server.util.StructuredLogger
import io.micrometer.core.instrument.MeterRegistry
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import org.springframework.stereotype.Component

/**
 * MCP tool: **search_global**
 *
 * Searches messages across all chats the user participates in.
 */
@Component
class SearchGlobalTool(
    private val telegramClient: TelegramClientService,
    private val guardrailService: GuardrailService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {

    private val log = StructuredLogger.forClass<SearchGlobalTool>()

    companion object {
        const val TOOL_NAME = "search_global"
        private const val MAX_QUERY_LENGTH = 256
        private const val MAX_LIMIT = 100

        @Suppress("JsonStandardCompliance")
        private val INPUT_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "query": {
              "type": "string",
              "description": "Search query (max 256 characters)"
            },
            "limit": {
              "type": "number",
              "description": "Max results to return (1-100, default: 20)"
            }
          },
          "required": ["query"]
        }
        """.trimIndent()
    }

    override fun definition(): McpSchema.Tool = ToolSupport.definition(
        name = TOOL_NAME,
        description = "Search messages across all Telegram chats",
        inputSchema = INPUT_SCHEMA,
        objectMapper = objectMapper,
    )

    override fun execute(
        exchange: McpSyncServerExchange,
        arguments: Map<String, Any>,
    ): McpSchema.CallToolResult = ToolSupport.execute(
        toolName = TOOL_NAME,
        arguments = arguments,
        objectMapper = objectMapper,
        meterRegistry = meterRegistry,
        log = log,
        failureMessage = "Global search failed",
        auditService = auditService,
    ) {
            val query = arguments["query"]?.toString()
                ?: throw InvalidToolInputException("query is required")
            if (query.isBlank()) throw InvalidToolInputException("query must not be blank")
            if (query.length > MAX_QUERY_LENGTH) {
                throw InvalidToolInputException("query exceeds maximum length of $MAX_QUERY_LENGTH characters")
            }

            val limit = (arguments["limit"] as? Number)?.toInt()?.coerceIn(1, MAX_LIMIT) ?: 20

            log.withTool(TOOL_NAME).info("Global search for '{}' (limit={})", query, limit)

            guardrailService.validateInput(query)
            telegramClient.searchGlobal(query, limit)
        }
}
