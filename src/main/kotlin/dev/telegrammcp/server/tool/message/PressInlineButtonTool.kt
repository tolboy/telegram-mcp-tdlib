package dev.telegrammcp.server.tool.message

import dev.telegrammcp.server.tool.ToolNumbers
import com.fasterxml.jackson.databind.ObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.config.McpSecurityProperties
import dev.telegrammcp.server.exception.InvalidToolInputException
import dev.telegrammcp.server.model.AuditOutcome
import dev.telegrammcp.server.model.STALE_INLINE_BUTTON_MESSAGE
import dev.telegrammcp.server.service.AuditService
import dev.telegrammcp.server.service.DestructiveApprovalService.InlineButtonTarget
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
 * press on a different button. Operators can opt in to deprecated selectors
 * for older clients while migrating.
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
    private val security: McpSecurityProperties = McpSecurityProperties(),
) : McpToolHandler {

    private val log = StructuredLogger.forClass<PressInlineButtonTool>()

    companion object {
        const val TOOL_NAME = "press_inline_button"
        private const val MAX_TOKEN_LENGTH = 128
        const val DEPRECATED_SELECTOR_WARNING = "button_index and button_text are deprecated and pick whatever button " +
            "is there at press time. Use button_token from list_inline_buttons instead."

        private val TOKEN_PROPERTIES = """
        "chat_id": { "type": ["string", "integer"], "description": "Chat identifier (ID/@username/+phone)" },
        "message_id": { "type": "integer", "description": "Target message ID" },
        "button_token": { "type": "string", "maxLength": 128, "description": "Signed token from list_inline_buttons" }
        """.trimIndent()
        private val LEGACY_PROPERTIES = """
        "button_index": { "type": "integer", "minimum": 0, "deprecated": true, "description": "Deprecated: use button_token. Zero-based position across all rows, resolved at press time." },
        "button_text": { "type": "string", "deprecated": true, "description": "Deprecated: use button_token. Visible button text (case-insensitive), resolved at press time." }
        """.trimIndent()
    }

    override fun definition(): McpSchema.Tool =
        dev.telegrammcp.server.tool.ToolSupport.definition(
            TOOL_NAME,
            "Press a callback or URL inline-keyboard button on a message. Call list_inline_buttons first and pass the " +
                "button's token as button_token. If the selected button or message edit timestamp fails verification, " +
                "list the buttons again.",
            inputSchema(),
            objectMapper,
        )

    override fun execute(
        exchange: McpSyncServerExchange,
        arguments: Map<String, Any>,
    ): McpSchema.CallToolResult = executeWithApproval(arguments) {}

    /** Allows the MCP dispatcher to ask about the token-bound target before any press. */
    internal fun executeWithApproval(
        arguments: Map<String, Any>,
        approve: (InlineButtonTarget?) -> Unit,
    ): McpSchema.CallToolResult {
        val sample = Timer.start(meterRegistry)

        return try {
            operationGuardService.checkPermission(TOOL_NAME, arguments)

            val token = parseToken(arguments)
            val index = ToolNumbers.specifiedInt(arguments, "button_index")
            val text = (arguments["button_text"] as? String)?.takeIf { it.isNotBlank() }
            if (token == null && index == null && text == null) {
                throw InvalidToolInputException("Pass button_token from list_inline_buttons")
            }
            val chatId = entityResolver.resolve(
                arguments["chat_id"] ?: throw InvalidToolInputException("chat_id is required"),
            )
            val messageId = ToolNumbers.long(
                arguments["message_id"] ?: throw InvalidToolInputException("message_id is required and must be a number"),
                "message_id",
            )
            guardrailService.validateChatAccess(chatId)

            val response = if (token != null) {
                val current = telegramClient.listInlineButtons(chatId, messageId)
                val target = buttonTokens.find(token, chatId, messageId, current)
                    ?: throw InvalidToolInputException(STALE_INLINE_BUTTON_MESSAGE)
                approve(InlineButtonTarget(chatId, messageId, target.position, target.text, target.type))
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
                approve(null)
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

    private fun parseToken(arguments: Map<String, Any>): String? {
        if ("button_token" !in arguments) {
            if (security.allowLegacyInlineButtonSelectors) return null
            throw InvalidToolInputException("button_token is required: call list_inline_buttons and pass the button's token")
        }
        if ("button_index" in arguments || "button_text" in arguments) {
            throw InvalidToolInputException("Pass button_token alone, without the deprecated button_index or button_text")
        }
        val token = (arguments["button_token"] as? String)?.trim()
        if (token == null || token.length > MAX_TOKEN_LENGTH || !token.startsWith(InlineButtonTokens.PREFIX)) {
            throw InvalidToolInputException("button_token is not a token from list_inline_buttons")
        }
        return token
    }

    private fun inputSchema(): String {
        val properties = if (security.allowLegacyInlineButtonSelectors) "$TOKEN_PROPERTIES, $LEGACY_PROPERTIES" else TOKEN_PROPERTIES
        val required = if (security.allowLegacyInlineButtonSelectors) "\"chat_id\", \"message_id\""
            else "\"chat_id\", \"message_id\", \"button_token\""
        return """{"type":"object","properties":{$properties},"required":[$required]}"""
    }
}
