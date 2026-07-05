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
 * MCP tool: **get_messages**
 *
 * Retrieves messages around a specific message ID, providing context for
 * a particular point in a conversation.
 *
 * ### Input schema
 * ```json
 * {
 *   "chat_id": "123456789",
 *   "message_id": 42,
 *   "context_size": 5
 * }
 * ```
 */
@Component
class GetMessagesTool(
    private val telegramClient: TelegramClientService,
    private val entityResolver: EntityResolverService,
    private val guardrailService: GuardrailService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {

    private val log = StructuredLogger.forClass<GetMessagesTool>()

    companion object {
        const val TOOL_NAME = "get_messages"
        private const val MAX_CONTEXT = 50
        private const val DEFAULT_CONTEXT = 5

        @Suppress("JsonStandardCompliance")
        private val INPUT_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "chat_id": {
              "type": ["string", "number"],
              "description": "Chat identifier: numeric ID, @username, +phone, or the canonical value self"
            },
            "message_id": {
              "type": "number",
              "description": "Target message ID to get context around"
            },
            "context_size": {
              "type": "number",
              "description": "Number of messages before and after (1–50, default 5)"
            }
          },
          "required": ["chat_id", "message_id"]
        }
        """.trimIndent()
    }

    override fun definition(): McpSchema.Tool = ToolSupport.definition(
        name = TOOL_NAME,
        description = "Get messages around a specific message ID for context",
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
        failureMessage = "Failed to get messages",
    ) {
            val chatId = resolveChatId(arguments)
            val messageId = extractMessageId(arguments)
            val contextSize = extractContextSize(arguments)

            log.withTool(TOOL_NAME).info(
                "Getting context around message {} in chat {} (±{})", messageId, chatId, contextSize,
            )

            guardrailService.validateChatAccess(chatId)

            // Get messages around the target: contextSize before + 1 (target) + contextSize after
            val totalLimit = contextSize * 2 + 1
            telegramClient.getHistory(
                chatId = chatId,
                fromMessageId = messageId,
                offset = -contextSize, // negative offset = messages after the target
                limit = totalLimit,
            )
        }

    private fun resolveChatId(args: Map<String, Any>): Long {
        val raw = args["chat_id"] ?: throw InvalidToolInputException("chat_id is required")
        return entityResolver.resolve(raw)
    }

    private fun extractMessageId(args: Map<String, Any>): Long {
        val raw = args["message_id"] ?: throw InvalidToolInputException("message_id is required")
        return when (raw) {
            is Number -> raw.toLong()
            is String -> raw.toLongOrNull()
                ?: throw InvalidToolInputException("message_id must be a valid number")
            else -> throw InvalidToolInputException("message_id must be a number")
        }
    }

    private fun extractContextSize(args: Map<String, Any>): Int {
        val raw = args["context_size"] ?: return DEFAULT_CONTEXT
        val size = when (raw) {
            is Number -> raw.toInt()
            is String -> raw.toIntOrNull()
                ?: throw InvalidToolInputException("context_size must be a valid integer")
            else -> throw InvalidToolInputException("context_size must be a number")
        }
        return size.coerceIn(1, MAX_CONTEXT)
    }
}

