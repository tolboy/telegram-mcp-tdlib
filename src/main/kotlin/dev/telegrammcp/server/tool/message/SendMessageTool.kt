package dev.telegrammcp.server.tool.message

import com.fasterxml.jackson.databind.ObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.exception.InvalidToolInputException
import dev.telegrammcp.server.model.ReplyMarkupSpec
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
 * MCP tool: **send_message**
 *
 * Sends a text message to a Telegram chat with optional HTML/Markdown formatting.
 *
 * ### Input schema
 * ```json
 * {
 *   "chat_id": "123456789",
 *   "text": "Hello, world!",
 *   "parse_mode": "html"
 * }
 * ```
 *
 * | Parameter  | Type          | Required | Default | Description                        |
 * |------------|---------------|----------|---------|------------------------------------|
 * | chat_id    | string/number | yes      | —       | Chat identifier (ID/@user/+phone)  |
 * | text       | string        | yes      | —       | Message text                       |
 * | parse_mode | string        | no       | "plain" | "plain", "html", or "markdown"     |
 * | message_thread_id | string/number | no | — | Forum topic message thread ID |
 */
@Component
class SendMessageTool(
    private val telegramClient: TelegramClientService,
    private val entityResolver: EntityResolverService,
    private val guardrailService: GuardrailService,
    private val operationGuardService: OperationGuardService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {

    private val log = StructuredLogger.forClass<SendMessageTool>()

    companion object {
        const val TOOL_NAME = "send_message"
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
            "text": {
              "type": "string",
              "description": "Message text (max 4096 characters)"
            },
            "parse_mode": {
              "type": "string",
              "enum": ["plain", "html", "markdown"],
              "description": "Text formatting mode (default: plain)"
            },
            "message_thread_id": {
              "type": ["string", "number"],
              "description": "Optional forum topic message thread ID"
            },
            "reply_markup": {
              "type": "object",
              "description": "Optional reply markup. Either a custom reply keyboard or a remove-keyboard marker.",
              "properties": {
                "type": {
                  "type": "string",
                  "enum": ["show_keyboard", "remove"],
                  "description": "show_keyboard to attach buttons, remove to clear existing keyboard"
                },
                "rows": {
                  "type": "array",
                  "items": { "type": "array", "items": { "type": "string" } },
                  "description": "Rows of button labels (only for show_keyboard)"
                },
                "one_time": { "type": "boolean", "description": "Hide keyboard after first tap (default true)" },
                "resize": { "type": "boolean", "description": "Resize keyboard to fit buttons (default true)" },
                "placeholder": { "type": "string", "description": "Placeholder text in the input field while keyboard is active" }
              },
              "required": ["type"]
            }
          },
          "required": ["chat_id", "text"]
        }
        """.trimIndent()
    }

    override fun definition(): McpSchema.Tool = ToolSupport.definition(
        TOOL_NAME,
        "Send a text message to a Telegram chat with optional HTML/Markdown formatting",
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
        failureMessage = "Failed to send message",
        auditService = auditService,
    ) {
            operationGuardService.checkPermission(TOOL_NAME, arguments)

            val chatId = resolveChatId(arguments)
            val text = extractText(arguments)
            val parseMode = ToolInputParsers.parseMode(arguments)
            val messageThreadId = parseOptionalLong(arguments["message_thread_id"], "message_thread_id")
            val replyMarkup = parseReplyMarkup(arguments["reply_markup"])

            log.withTool(TOOL_NAME).info(
                "Sending message to chat {} (thread={}, length={}, parseMode={}, replyMarkup={})",
                chatId, messageThreadId, text.length, parseMode, replyMarkup?.type,
            )

            guardrailService.validateInput(text)
            guardrailService.validateChatAccess(chatId)

            telegramClient.sendMessage(chatId, text, parseMode, replyMarkup, messageThreadId)
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseReplyMarkup(raw: Any?): ReplyMarkupSpec? {
        if (raw == null) return null
        val map = raw as? Map<String, Any?>
            ?: throw InvalidToolInputException("reply_markup must be an object")
        val typeStr = map["type"]?.toString()?.lowercase()
            ?: throw InvalidToolInputException("reply_markup.type is required")

        return when (typeStr) {
            "remove" -> ReplyMarkupSpec(type = ReplyMarkupSpec.Kind.REMOVE)
            "show_keyboard" -> {
                val rawRows = map["rows"] as? List<*>
                    ?: throw InvalidToolInputException("reply_markup.rows is required for show_keyboard")
                val rows = rawRows.mapIndexed { rowIdx, row ->
                    val buttons = row as? List<*>
                        ?: throw InvalidToolInputException("reply_markup.rows[$rowIdx] must be an array of strings")
                    buttons.mapIndexed { btnIdx, btn ->
                        val txt = btn?.toString()?.takeIf { it.isNotBlank() }
                            ?: throw InvalidToolInputException("reply_markup.rows[$rowIdx][$btnIdx] must be a non-blank string")
                        if (txt.length > 64) {
                            throw InvalidToolInputException("reply_markup button text must be 64 characters or fewer (row $rowIdx, index $btnIdx)")
                        }
                        txt
                    }
                }
                if (rows.isEmpty() || rows.all { it.isEmpty() }) {
                    throw InvalidToolInputException("reply_markup.rows must contain at least one button")
                }
                ReplyMarkupSpec(
                    type = ReplyMarkupSpec.Kind.SHOW_KEYBOARD,
                    rows = rows,
                    oneTime = map["one_time"]?.toString()?.toBoolean() ?: true,
                    resize = map["resize"]?.toString()?.toBoolean() ?: true,
                    placeholder = map["placeholder"]?.toString()?.takeIf { it.isNotBlank() },
                )
            }
            else -> throw InvalidToolInputException("reply_markup.type must be one of: show_keyboard, remove")
        }
    }

    private fun resolveChatId(args: Map<String, Any>): Long {
        val raw = args["chat_id"] ?: throw InvalidToolInputException("chat_id is required")
        return entityResolver.resolve(raw)
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

    private fun parseOptionalLong(raw: Any?, field: String): Long? {
        if (raw == null) return null
        return when (raw) {
            is Number -> raw.toLong()
            is String -> raw.trim().takeIf { it.isNotBlank() }?.toLongOrNull()
                ?: throw InvalidToolInputException("$field must be an integer")
            else -> throw InvalidToolInputException("$field must be an integer")
        }
    }

}

