package dev.telegrammcp.server.tool.chat

import com.fasterxml.jackson.databind.ObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.exception.InvalidToolInputException
import dev.telegrammcp.server.model.ChatType
import dev.telegrammcp.server.tool.McpToolHandler
import dev.telegrammcp.server.tool.ToolSupport
import dev.telegrammcp.server.util.StructuredLogger
import io.micrometer.core.instrument.MeterRegistry
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import org.springframework.stereotype.Component

/**
 * MCP tool: **list_chats**
 *
 * Lists Telegram chats (dialogs) the user/bot is part of, with optional
 * filtering by chat type and unread status.
 *
 * ### Input schema
 * ```json
 * {
 *   "limit": 50,
 *   "filter": "all",
 *   "unread_only": false
 * }
 * ```
 */
@Component
class ListChatsTool(
    private val telegramClient: TelegramClientService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {

    private val log = StructuredLogger.forClass<ListChatsTool>()

    companion object {
        const val TOOL_NAME = "list_chats"
        private const val MAX_LIMIT = 200
        private const val DEFAULT_LIMIT = 50

        private val INPUT_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "limit": {
              "type": "number",
              "description": "Max number of chats to return (1–200, default 50)"
            },
            "filter": {
              "type": "string",
              "enum": ["all", "private", "group", "supergroup", "channel"],
              "description": "Filter chats by type (default: all)"
            },
            "unread_only": {
              "type": "boolean",
              "description": "Only return chats with unread messages (default: false)"
            }
          }
        }
        """.trimIndent()
    }

    override fun definition(): McpSchema.Tool = ToolSupport.definition(
        TOOL_NAME,
        "List Telegram chats with optional filtering by type and unread status",
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
        failureMessage = "Failed to list chats",
    ) {
            val limit = extractLimit(arguments)
            val filter = extractFilter(arguments)
            val unreadOnly = extractUnreadOnly(arguments)

            log.withTool(TOOL_NAME).info(
                "Listing chats (limit={}, filter={}, unreadOnly={})", limit, filter, unreadOnly,
            )

            var chats = telegramClient.getChats(limit)

            // Apply type filter
            if (filter != null) {
                chats = chats.filter { it.type == filter }
            }

            // Apply unread filter
            if (unreadOnly) {
                chats = chats.filter { (it.unreadCount ?: 0) > 0 }
            }

            chats
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

    private fun extractFilter(args: Map<String, Any>): ChatType? {
        val raw = args["filter"]?.toString()?.lowercase() ?: return null
        return when (raw) {
            "all" -> null
            "private" -> ChatType.PRIVATE
            "group" -> ChatType.GROUP
            "supergroup" -> ChatType.SUPERGROUP
            "channel" -> ChatType.CHANNEL
            else -> throw InvalidToolInputException("filter must be one of: all, private, group, supergroup, channel")
        }
    }

    private fun extractUnreadOnly(args: Map<String, Any>): Boolean {
        val raw = args["unread_only"] ?: return false
        return when (raw) {
            is Boolean -> raw
            is String -> raw.toBooleanStrictOrNull()
                ?: throw InvalidToolInputException("unread_only must be a boolean")
            else -> throw InvalidToolInputException("unread_only must be a boolean")
        }
    }
}

