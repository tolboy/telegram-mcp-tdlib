package dev.telegrammcp.server.tool.user

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
 * MCP tool: **search_contacts** — searches the local contact list by name.
 */
@Component
class SearchContactsTool(
    private val telegramClient: TelegramClientService,
    private val guardrailService: GuardrailService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {

    private val log = StructuredLogger.forClass<SearchContactsTool>()

    companion object {
        const val TOOL_NAME = "search_contacts"
        private const val DEFAULT_LIMIT = 50

        @Suppress("JsonStandardCompliance")
        private val INPUT_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "query": { "type": "string", "description": "Search query (name or username prefix)" },
            "limit": { "type": "number", "description": "Max results (1–200, default 50)" }
          },
          "required": ["query"]
        }
        """.trimIndent()
    }

    override fun definition(): McpSchema.Tool = ToolSupport.definition(
        name = TOOL_NAME,
        description = "Search the local contact list by name or username",
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
        failureMessage = "Failed to search contacts",
        auditService = auditService,
    ) {
            val query = arguments["query"]?.toString()?.takeIf { it.isNotBlank() }
                ?: throw InvalidToolInputException("query is required")
            val limit = (arguments["limit"] as? Number)?.toInt()
                ?: arguments["limit"]?.toString()?.toIntOrNull()
                ?: DEFAULT_LIMIT

            log.withTool(TOOL_NAME).info("Searching contacts for '{}' (limit={})", query, limit)
            guardrailService.validateInput(query)
            telegramClient.searchContactsByQuery(query, limit)
        }
}
