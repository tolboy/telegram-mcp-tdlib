package dev.telegrammcp.server.tool.message

import com.fasterxml.jackson.databind.ObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.exception.InvalidToolInputException
import dev.telegrammcp.server.service.EntityResolverService
import dev.telegrammcp.server.service.GuardrailService
import dev.telegrammcp.server.tool.McpToolHandler
import dev.telegrammcp.server.tool.ToolInputParsers
import dev.telegrammcp.server.util.StructuredLogger
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import org.springframework.stereotype.Component

/**
 * MCP tool: **edit_message**
 *
 * Edits the text of an existing message in a Telegram chat.
 * Only messages sent by the current user/bot can be edited.
 *
 * ### Input schema
 * ```json
 * {
 *   "chat_id": "123456789",
 *   "message_id": 42,
 *   "text": "Updated text",
 *   "parse_mode": "html"
 * }
 * ```
 */
@Component
class EditMessageTool(
    private val telegramClient: TelegramClientService,
    private val entityResolver: EntityResolverService,
    private val guardrailService: GuardrailService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {

    private val log = StructuredLogger.forClass<EditMessageTool>()

    companion object {
        const val TOOL_NAME = "edit_message"
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
              "description": "ID of the message to edit"
            },
            "text": {
              "type": "string",
              "description": "New message text (max 4096 characters)"
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
        dev.telegrammcp.server.tool.ToolSupport.definition(TOOL_NAME, "Edit the text of an existing message in a Telegram chat", INPUT_SCHEMA, objectMapper)

    override fun execute(
        exchange: McpSyncServerExchange,
        arguments: Map<String, Any>,
    ): McpSchema.CallToolResult {
        val sample = Timer.start(meterRegistry)

        return try {
            val chatId = resolveChatId(arguments)
            val messageId = extractMessageId(arguments)
            val text = extractText(arguments)
            val parseMode = ToolInputParsers.parseMode(arguments)

            log.withTool(TOOL_NAME).info(
                "Editing message {} in chat {} (parseMode={})", messageId, chatId, parseMode,
            )

            guardrailService.validateInput(text)
            guardrailService.validateChatAccess(chatId)

            val edited = telegramClient.editMessage(chatId, messageId, text, parseMode)
            val json = objectMapper.writeValueAsString(edited)

            dev.telegrammcp.server.tool.ToolSupport.textResult(json)
        } catch (ex: Exception) {
            log.withTool(TOOL_NAME).error("Failed to edit message: {}", ex.message, ex)
            dev.telegrammcp.server.tool.ToolSupport.errorText("Error: ${ex.message}")
        } finally {
            sample.stop(
                Timer.builder("mcp.tool.execution").tag("tool", TOOL_NAME).register(meterRegistry),
            )
        }
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

