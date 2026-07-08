package dev.telegrammcp.server.tool.message

import com.fasterxml.jackson.databind.ObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.exception.InvalidToolInputException
import dev.telegrammcp.server.service.AuditService
import dev.telegrammcp.server.service.EntityResolverService
import dev.telegrammcp.server.service.GuardrailService
import dev.telegrammcp.server.service.OperationGuardService
import dev.telegrammcp.server.tool.McpToolHandler
import dev.telegrammcp.server.tool.ToolInputParsers
import dev.telegrammcp.server.tool.ToolSupport
import dev.telegrammcp.server.util.StructuredLogger
import io.micrometer.core.instrument.MeterRegistry
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import org.springframework.stereotype.Component

/**
 * MCP tool: **reply_to_message**
 *
 * Replies to a specific message in a Telegram chat.
 *
 * ### Input schema
 * ```json
 * {
 *   "chat_id": "123456789",
 *   "message_id": 42,
 *   "text": "Great point!",
 *   "parse_mode": "plain"
 * }
 * ```
 */
@Component
class ReplyToMessageTool(
    private val telegramClient: TelegramClientService,
    private val entityResolver: EntityResolverService,
    private val guardrailService: GuardrailService,
    private val operationGuardService: OperationGuardService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {

    private val log = StructuredLogger.forClass<ReplyToMessageTool>()

    companion object {
        const val TOOL_NAME = "reply_to_message"
        private const val MAX_TEXT_LENGTH = 4096

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
              "description": "ID of the message to reply to"
            },
            "text": {
              "type": "string",
              "description": "Reply text (max 4096 characters)"
            },
            "parse_mode": {
              "type": "string",
              "enum": ["plain", "html", "markdown"],
              "description": "Text formatting mode (default: plain)"
            }
          },
          "required": ["chat_id", "message_id", "text"]
        }
        """.trimIndent()
    }

    override fun definition(): McpSchema.Tool =
        ToolSupport.definition(TOOL_NAME, "Reply to a specific message in a Telegram chat", INPUT_SCHEMA, objectMapper)

    override fun execute(
        exchange: McpSyncServerExchange,
        arguments: Map<String, Any>,
    ): McpSchema.CallToolResult = ToolSupport.execute(
        toolName = TOOL_NAME,
        arguments = arguments,
        objectMapper = objectMapper,
        meterRegistry = meterRegistry,
        log = log,
        failureMessage = "Failed to reply",
        auditService = auditService,
    ) {
        operationGuardService.checkPermission(TOOL_NAME, arguments)

        val chatId = resolveChatId(arguments)
        val messageId = extractMessageId(arguments)
        val text = extractText(arguments)
        val parseMode = ToolInputParsers.parseMode(arguments)

        log.withTool(TOOL_NAME).info(
            "Replying to message {} in chat {} (parseMode={})", messageId, chatId, parseMode,
        )

        guardrailService.validateInput(text)
        guardrailService.validateChatAccess(chatId)

        val reply = telegramClient.replyToMessage(chatId, messageId, text, parseMode)
        if (reply.replyToMessageId != messageId) {
            throw IllegalStateException("Telegram did not attach reply to message $messageId in chat $chatId")
        }
        objectMapper.writeValueAsString(reply)
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

    private fun extractText(args: Map<String, Any>): String {
        val text = args["text"]?.toString()
            ?: throw InvalidToolInputException("text is required")
        if (text.isBlank()) throw InvalidToolInputException("text must not be blank")
        if (text.length > MAX_TEXT_LENGTH) {
            throw InvalidToolInputException("text exceeds maximum length of $MAX_TEXT_LENGTH characters")
        }
        return text
    }

}

