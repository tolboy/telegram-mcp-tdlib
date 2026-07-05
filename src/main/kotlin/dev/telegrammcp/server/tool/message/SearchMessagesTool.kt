package dev.telegrammcp.server.tool.message

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
 * MCP tool: **search_messages**
 *
 * Full-text search within a specific Telegram chat.
 * Replaces the former `search_in_channels` stub with a TDLib-backed implementation.
 *
 * ### Input schema
 * ```json
 * {
 *   "chat_id": "123456789",
 *   "query": "kotlin coroutines",
 *   "limit": 20,
 *   "offset": 0
 * }
 * ```
 */
@Component
class SearchMessagesTool(
    private val telegramClient: TelegramClientService,
    private val entityResolver: EntityResolverService,
    private val guardrailService: GuardrailService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {

    private val log = StructuredLogger.forClass<SearchMessagesTool>()

    companion object {
        const val TOOL_NAME = "search_messages"
        private const val MAX_LIMIT = 100
        private const val DEFAULT_LIMIT = 20
        private const val MAX_QUERY_LENGTH = 256

        @Suppress("JsonStandardCompliance")
        private val INPUT_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "chat_id": {
              "type": ["string", "number"],
              "description": "Chat identifier: numeric ID, @username, +phone, or the canonical value self"
            },
            "query": {
              "type": "string",
              "description": "Search query string"
            },
            "limit": {
              "type": "number",
              "description": "Max results (1–100, default 20)"
            },
            "offset": {
              "type": "number",
              "description": "Pagination offset message ID (default 0)"
            }
          },
          "required": ["chat_id", "query"]
        }
        """.trimIndent()
    }

    override fun definition(): McpSchema.Tool = ToolSupport.definition(
        name = TOOL_NAME,
        description = "Search messages in a Telegram chat by keyword or phrase",
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
        failureMessage = "Search failed",
    ) {
            val chatId = resolveChatId(arguments)
            val query = extractQuery(arguments)
            val limit = extractLimit(arguments)
            val offset = extractOffset(arguments)

            log.withTool(TOOL_NAME).info(
                "Searching '{}' in chat {} (offset={}, limit={})", query, chatId, offset, limit,
            )

            guardrailService.validateInput(query)
            guardrailService.validateChatAccess(chatId)

            telegramClient.searchMessages(chatId, query, offset, limit)
        }

    private fun resolveChatId(args: Map<String, Any>): Long {
        val raw = args["chat_id"] ?: throw InvalidToolInputException("chat_id is required")
        return entityResolver.resolve(raw)
    }

    private fun extractQuery(args: Map<String, Any>): String {
        val query = args["query"]?.toString()
            ?: throw InvalidToolInputException("query is required")
        if (query.isBlank()) throw InvalidToolInputException("query must not be blank")
        if (query.length > MAX_QUERY_LENGTH) {
            throw InvalidToolInputException("query exceeds maximum length of $MAX_QUERY_LENGTH")
        }
        return query
    }

    private fun extractLimit(args: Map<String, Any>): Int {
        val raw = args["limit"] ?: return DEFAULT_LIMIT
        val limit = when (raw) {
            is Number -> raw.toInt()
            is String -> raw.toIntOrNull()
                ?: throw InvalidToolInputException("limit must be a valid integer")
            else -> throw InvalidToolInputException("limit must be a number")
        }
        return limit.coerceIn(1, MAX_LIMIT)
    }

    private fun extractOffset(args: Map<String, Any>): Long {
        val raw = args["offset"] ?: return 0L
        return when (raw) {
            is Number -> raw.toLong()
            is String -> raw.toLongOrNull()
                ?: throw InvalidToolInputException("offset must be a valid number")
            else -> throw InvalidToolInputException("offset must be a number")
        }
    }
}

