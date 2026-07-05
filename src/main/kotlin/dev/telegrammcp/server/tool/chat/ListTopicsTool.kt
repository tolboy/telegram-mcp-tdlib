package dev.telegrammcp.server.tool.chat

import com.fasterxml.jackson.databind.ObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.exception.InvalidToolInputException
import dev.telegrammcp.server.service.EntityResolverService
import dev.telegrammcp.server.service.GuardrailService
import dev.telegrammcp.server.tool.McpToolHandler
import dev.telegrammcp.server.tool.ToolSupport
import dev.telegrammcp.server.util.StructuredLogger
import io.micrometer.core.instrument.MeterRegistry
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import org.springframework.stereotype.Component

/**
 * MCP tool: **list_topics**
 *
 * Lists forum topics in a supergroup that has topics enabled.
 */
@Component
class ListTopicsTool(
    private val telegramClient: TelegramClientService,
    private val entityResolver: EntityResolverService,
    private val guardrailService: GuardrailService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {

    private val log = StructuredLogger.forClass<ListTopicsTool>()

    companion object {
        const val TOOL_NAME = "list_topics"
        private const val MAX_LIMIT = 100
        private const val DEFAULT_LIMIT = 50

        @Suppress("JsonStandardCompliance")
        private val INPUT_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "chat_id": {
              "type": ["string", "number"],
              "description": "Supergroup chat identifier (ID/@username/+phone)"
            },
            "query": {
              "type": "string",
              "description": "Filter topics by name (optional)"
            },
            "limit": {
              "type": "number",
              "description": "Max topics to return (1-100, default 50)"
            }
          },
          "required": ["chat_id"]
        }
        """.trimIndent()
    }

    override fun definition(): McpSchema.Tool = ToolSupport.definition(
        name = TOOL_NAME,
        description = "List forum topics in a Telegram supergroup",
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
        failureMessage = "Failed to list forum topics",
    ) {
            val chatId = entityResolver.resolve(
                arguments["chat_id"] ?: throw InvalidToolInputException("chat_id is required"),
            )
            val query = arguments["query"]?.toString() ?: ""
            val limit = ((arguments["limit"] as? Number)?.toInt() ?: DEFAULT_LIMIT)
                .coerceIn(1, MAX_LIMIT)

            guardrailService.validateChatAccess(chatId)
            log.withTool(TOOL_NAME).info(
                "Listing forum topics in chat {} (query='{}', limit={})", chatId, query, limit,
            )
            telegramClient.listForumTopics(chatId, query, limit)
        }
}
