package dev.telegrammcp.server.tool.message

import dev.telegrammcp.server.tool.ToolNumbers
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
 * MCP tool: **list_inline_buttons**
 *
 * Inspects the inline-keyboard buttons attached to a message. Every button
 * carries a signed `token` that press_inline_button takes as `button_token`.
 */
@Component
class ListInlineButtonsTool(
    private val telegramClient: TelegramClientService,
    private val entityResolver: EntityResolverService,
    private val guardrailService: GuardrailService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
    private val buttonTokens: InlineButtonTokens = InlineButtonTokens(),
) : McpToolHandler {

    private val log = StructuredLogger.forClass<ListInlineButtonsTool>()

    companion object {
        const val TOOL_NAME = "list_inline_buttons"

        @Suppress("JsonStandardCompliance")
        private val INPUT_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "chat_id": { "type": ["string", "integer"], "description": "Chat identifier (ID/@username/+phone)" },
            "message_id": { "type": "integer", "description": "Target message ID" }
          },
          "required": ["chat_id", "message_id"]
        }
        """.trimIndent()
    }

    override fun definition(): McpSchema.Tool = ToolSupport.definition(
        name = TOOL_NAME,
        description = "List inline-keyboard buttons attached to a Telegram message. Each button has a token: pass it " +
            "as button_token to press_inline_button. A token stops working when the message or keyboard changes.",
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
        failureMessage = "Failed to list inline buttons",
    ) {
            val chatId = entityResolver.resolve(
                arguments["chat_id"] ?: throw InvalidToolInputException("chat_id is required"),
            )
            val messageId = ToolNumbers.long(arguments["message_id"] ?: throw InvalidToolInputException("message_id is required and must be a number"), "message_id")

            guardrailService.validateChatAccess(chatId)
            log.withTool(TOOL_NAME).info("Listing inline buttons for message {} in chat {}", messageId, chatId)
            buttonTokens.sign(chatId, messageId, telegramClient.listInlineButtons(chatId, messageId))
        }
}
