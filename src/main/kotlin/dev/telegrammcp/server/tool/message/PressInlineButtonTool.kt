package dev.telegrammcp.server.tool.message

import dev.telegrammcp.server.tool.ToolNumbers
import com.fasterxml.jackson.databind.ObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.exception.InvalidToolInputException
import dev.telegrammcp.server.model.AuditOutcome
import dev.telegrammcp.server.model.STALE_INLINE_BUTTON_MESSAGE
import dev.telegrammcp.server.service.AuditService
import dev.telegrammcp.server.service.EntityResolverService
import dev.telegrammcp.server.service.GuardrailService
import dev.telegrammcp.server.service.OperationGuardService
import dev.telegrammcp.server.tool.McpToolHandler
import dev.telegrammcp.server.util.StructuredLogger
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import org.springframework.stereotype.Component

/**
 * MCP tool: **press_inline_button**
 *
 * Presses an inline-keyboard button on a message. Supports callback buttons
 * (returns the bot's answer text) and URL/web-app buttons (returns the URL).
 *
 * The button is chosen by the signed `button_token` from list_inline_buttons.
 * The token is checked against a fresh read of the message, so a bot that
 * edits the keyboard between listing and pressing gets an error instead of a
 * press on a different button. `button_index` and `button_text` still work
 * for existing clients but are deprecated: they pick whatever button is in
 * that place, or has that label, at press time.
 */
@Component
class PressInlineButtonTool(
    private val telegramClient: TelegramClientService,
    private val entityResolver: EntityResolverService,
    private val guardrailService: GuardrailService,
    private val operationGuardService: OperationGuardService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
    private val buttonTokens: InlineButtonTokens = InlineButtonTokens(),
) : McpToolHandler {

    private val log = StructuredLogger.forClass<PressInlineButtonTool>()

    companion object {
        const val TOOL_NAME = "press_inline_button"
        private const val MAX_TOKEN_LENGTH = 128
        const val DEPRECATED_SELECTOR_WARNING = "button_index and button_text are deprecated and pick whatever button " +
            "is there at press time. Use button_token from list_inline_buttons instead."

        @Suppress("JsonStandardCompliance")
        private val INPUT_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "chat_id": { "type": ["string", "integer"], "description": "Chat identifier (ID/@username/+phone)" },
            "message_id": { "type": "integer", "description": "Target message ID" },
            "button_token": { "type": "string", "maxLength": 128, "description": "Token of the button from list_inline_buttons. Preferred: it presses exactly the button you saw and fails if the keyboard changed since." },
            "button_index": { "type": "integer", "minimum": 0, "deprecated": true, "description": "Deprecated: use button_token. Zero-based position across all rows, resolved at press time." },
            "button_text": { "type": "string", "deprecated": true, "description": "Deprecated: use button_token. Visible button text (case-insensitive), resolved at press time." }
          },
          "required": ["chat_id", "message_id"]
        }
        """.trimIndent()
    }

    override fun definition(): McpSchema.Tool =
        dev.telegrammcp.server.tool.ToolSupport.definition(
            TOOL_NAME,
            "Press a callback or URL inline-keyboard button on a message. Call list_inline_buttons first and pass the " +
                "button's token as button_token; if the keyboard changed since, nothing is pressed and you must list again.",
            INPUT_SCHEMA,
            objectMapper,
        )

    override fun execute(
        exchange: McpSyncServerExchange,
        arguments: Map<String, Any>,
    ): McpSchema.CallToolResult {
        val sample = Timer.start(meterRegistry)

        return try {
            operationGuardService.checkPermission(TOOL_NAME, arguments)

            val chatId = entityResolver.resolve(
                arguments["chat_id"] ?: throw InvalidToolInputException("chat_id is required"),
            )
            val messageId = ToolNumbers.long(arguments["message_id"] ?: throw InvalidToolInputException("message_id is required and must be a number"), "message_id")

            val token = arguments["button_token"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            val index = ToolNumbers.specifiedInt(arguments, "button_index")
            val text = arguments["button_text"]?.toString()?.takeIf { it.isNotBlank() }
            if (token != null && (index != null || text != null)) {
                throw InvalidToolInputException("Pass button_token alone, without the deprecated button_index or button_text")
            }
            if (token == null && index == null && text == null) {
                throw InvalidToolInputException(
                    "button_token is required: call list_inline_buttons and pass the button's token " +
                        "(the deprecated button_index or button_text are still accepted)",
                )
            }
            if (token != null && (token.length > MAX_TOKEN_LENGTH || !token.startsWith(InlineButtonTokens.PREFIX))) {
                throw InvalidToolInputException("button_token is not a token from list_inline_buttons")
            }

            guardrailService.validateChatAccess(chatId)

            val response = if (token != null) {
                val current = telegramClient.listInlineButtons(chatId, messageId)
                val target = buttonTokens.find(token, chatId, messageId, current)
                    ?: throw InvalidToolInputException(STALE_INLINE_BUTTON_MESSAGE)
                log.withTool(TOOL_NAME).info(
                    "Pressing inline button by token (position={}) on message {} in chat {}", target.position, messageId, chatId,
                )
                val answer = telegramClient.pressInlineButton(chatId, messageId, target.position, null, target)
                linkedMapOf<String, Any?>(
                    "chat_id" to chatId,
                    "message_id" to messageId,
                    "button_index" to target.position,
                    "button_text" to target.text,
                    "answer" to answer,
                    "warning" to null,
                )
            } else {
                log.withTool(TOOL_NAME).warn(
                    "Pressing inline button by deprecated selector (idx={}, text={}) on message {} in chat {}",
                    index, text, messageId, chatId,
                )
                val answer = telegramClient.pressInlineButton(chatId, messageId, index, text)
                linkedMapOf<String, Any?>(
                    "chat_id" to chatId,
                    "message_id" to messageId,
                    "button_index" to index,
                    "button_text" to text,
                    "answer" to answer,
                    "warning" to DEPRECATED_SELECTOR_WARNING,
                )
            }
            auditService.record(TOOL_NAME, arguments, AuditOutcome.SUCCESS)

            val json = objectMapper.writeValueAsString(response)
            dev.telegrammcp.server.tool.ToolSupport.textResult(json)
        } catch (ex: Exception) {
            log.withTool(TOOL_NAME).error("Failed to press inline button: {}", ex.message, ex)
            auditService.record(TOOL_NAME, arguments, AuditService.outcomeFor(ex), error = ex.message)
            dev.telegrammcp.server.tool.ToolSupport.errorResult(ex)
        } finally {
            sample.stop(Timer.builder("mcp.tool.execution").tag("tool", TOOL_NAME).register(meterRegistry))
        }
    }
}
