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
 * MCP tool: **get_participants**
 *
 * Lists members of a Telegram group, supergroup, or channel with optional
 * search filtering.
 *
 * ### Input schema
 * ```json
 * {
 *   "chat_id": "123456789",
 *   "query": "",
 *   "limit": 50,
 *   "offset": 0
 * }
 * ```
 */
@Component
class GetParticipantsTool(
    private val telegramClient: TelegramClientService,
    private val entityResolver: EntityResolverService,
    private val guardrailService: GuardrailService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {

    private val log = StructuredLogger.forClass<GetParticipantsTool>()

    companion object {
        const val TOOL_NAME = "get_participants"
        private const val MAX_LIMIT = 200
        private const val DEFAULT_LIMIT = 50

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
              "description": "Filter members by name or username (optional)"
            },
            "limit": {
              "type": "number",
              "description": "Max members to return (1–200, default 50)"
            },
            "offset": {
              "type": "number",
              "description": "Pagination offset (default 0)"
            }
          },
          "required": ["chat_id"]
        }
        """.trimIndent()
    }

    override fun definition(): McpSchema.Tool = ToolSupport.definition(
        name = TOOL_NAME,
        description = "List members of a Telegram group, supergroup, or channel",
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
        failureMessage = "Failed to get participants",
    ) {
            val chatId = resolveChatId(arguments)
            val query = arguments["query"]?.toString() ?: ""
            val limit = extractLimit(arguments)
            val offset = extractOffset(arguments)

            log.withTool(TOOL_NAME).info(
                "Getting participants of chat {} (query='{}', offset={}, limit={})",
                chatId, query, offset, limit,
            )

            guardrailService.validateChatAccess(chatId)
            telegramClient.getChatMembers(chatId, query, offset, limit)
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

    private fun extractOffset(args: Map<String, Any>): Int {
        val raw = args["offset"] ?: return 0
        return when (raw) {
            is Number -> raw.toInt()
            is String -> raw.toIntOrNull()
                ?: throw InvalidToolInputException("offset must be a valid integer")
            else -> throw InvalidToolInputException("offset must be a number")
        }
    }
}

