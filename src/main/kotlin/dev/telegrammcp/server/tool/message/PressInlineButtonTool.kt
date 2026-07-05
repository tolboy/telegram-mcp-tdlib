package dev.telegrammcp.server.tool.message

import com.fasterxml.jackson.databind.ObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.exception.InvalidToolInputException
import dev.telegrammcp.server.model.AuditOutcome
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
) : McpToolHandler {

    private val log = StructuredLogger.forClass<PressInlineButtonTool>()

    companion object {
        const val TOOL_NAME = "press_inline_button"

        @Suppress("JsonStandardCompliance")
        private val INPUT_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "chat_id": { "type": ["string", "number"], "description": "Chat identifier (ID/@username/+phone)" },
            "message_id": { "type": "number", "description": "Target message ID" },
            "button_index": { "type": "number", "description": "Zero-based flattened button index" },
            "button_text": { "type": "string", "description": "Match button by its visible text (case-insensitive)" }
          },
          "required": ["chat_id", "message_id"]
        }
        """.trimIndent()
    }

    override fun definition(): McpSchema.Tool =
        dev.telegrammcp.server.tool.ToolSupport.definition(TOOL_NAME, "Press a callback or URL inline-keyboard button on a message", INPUT_SCHEMA, objectMapper)

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
            val messageId = (arguments["message_id"] as? Number)?.toLong()
                ?: arguments["message_id"]?.toString()?.toLongOrNull()
                ?: throw InvalidToolInputException("message_id is required and must be a number")

            val index = (arguments["button_index"] as? Number)?.toInt()
                ?: arguments["button_index"]?.toString()?.toIntOrNull()
            val text = arguments["button_text"]?.toString()?.takeIf { it.isNotBlank() }
            if (index == null && text == null) {
                throw InvalidToolInputException("Either button_index or button_text is required")
            }

            guardrailService.validateChatAccess(chatId)
            log.withTool(TOOL_NAME).info(
                "Pressing inline button (idx={}, text={}) on message {} in chat {}", index, text, messageId, chatId,
            )

            val result = telegramClient.pressInlineButton(chatId, messageId, index, text)
            auditService.record(TOOL_NAME, arguments, AuditOutcome.SUCCESS)

            val json = objectMapper.writeValueAsString(
                mapOf(
                    "chat_id" to chatId,
                    "message_id" to messageId,
                    "button_index" to index,
                    "button_text" to text,
                    "answer" to result,
                ),
            )
            dev.telegrammcp.server.tool.ToolSupport.textResult(json)
        } catch (ex: Exception) {
            log.withTool(TOOL_NAME).error("Failed to press inline button: {}", ex.message, ex)
            auditService.record(TOOL_NAME, arguments, AuditOutcome.ERROR, error = ex.message)
            dev.telegrammcp.server.tool.ToolSupport.errorText("Error: ${ex.message}")
        } finally {
            sample.stop(Timer.builder("mcp.tool.execution").tag("tool", TOOL_NAME).register(meterRegistry))
        }
    }
}
