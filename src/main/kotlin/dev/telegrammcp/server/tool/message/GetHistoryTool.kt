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
 * MCP tool: **get_history**
 *
 * Retrieves the most recent messages from a Telegram chat with pagination support.
 * Replaces the former `get_recent_messages` stub with a fully functional TDLib implementation.
 *
 * ### Input schema
 * ```json
 * {
 *   "chat_id": "123456789",
 *   "limit": 20,
 *   "from_message_id": 0
 * }
 * ```
 *
 * | Parameter       | Type          | Required | Default | Description                           |
 * |-----------------|---------------|----------|---------|---------------------------------------|
 * | chat_id         | string/number | yes      | —       | Chat identifier (ID, @username, +phone) |
 * | limit           | number        | no       | 20      | Number of messages (1–100)            |
 * | from_message_id | number        | no       | 0       | Pagination cursor (0 = latest)        |
 */
@Component
class GetHistoryTool(
    private val telegramClient: TelegramClientService,
    private val entityResolver: EntityResolverService,
    private val guardrailService: GuardrailService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {

    private val log = StructuredLogger.forClass<GetHistoryTool>()

    companion object {
        const val TOOL_NAME = "get_history"
        private const val MAX_LIMIT = 100
        private const val DEFAULT_LIMIT = 20

        @Suppress("JsonStandardCompliance")
        private val INPUT_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "chat_id": {
              "type": ["string", "number"],
              "description": "Chat identifier: numeric ID, @username, +phone, or the canonical value self"
            },
            "limit": {
              "type": "number",
              "description": "Number of messages to retrieve (1–100, default 20)"
            },
            "from_message_id": {
              "type": "number",
              "description": "Start from this message ID for pagination (0 = latest)"
            }
          },
          "required": ["chat_id"]
        }
        """.trimIndent()
    }

    override fun definition(): McpSchema.Tool = ToolSupport.definition(
        TOOL_NAME,
        "Retrieve recent messages from a Telegram chat with pagination support",
        INPUT_SCHEMA,
        objectMapper,
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
        failureMessage = "Failed to get history",
    ) {
            val chatId = resolveChatId(arguments)
            val limit = extractLimit(arguments)
            val fromMessageId = extractFromMessageId(arguments)

            log.withTool(TOOL_NAME).info(
                "Fetching {} messages from chat {} (from={})", limit, chatId, fromMessageId,
            )

            guardrailService.validateChatAccess(chatId)

            telegramClient.getHistory(chatId, fromMessageId, 0, limit)
    }

    private fun resolveChatId(args: Map<String, Any>): Long {
        val raw = args["chat_id"] ?: throw InvalidToolInputException("chat_id is required")
        return entityResolver.resolve(raw)
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

    private fun extractFromMessageId(args: Map<String, Any>): Long {
        val raw = args["from_message_id"] ?: return 0L
        return when (raw) {
            is Number -> raw.toLong()
            is String -> raw.toLongOrNull()
                ?: throw InvalidToolInputException("from_message_id must be a valid number")
            else -> throw InvalidToolInputException("from_message_id must be a number")
        }
    }
}

