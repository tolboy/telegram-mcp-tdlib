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
 * MCP tool: **list_inline_buttons**
 *
 * Inspects the inline-keyboard buttons attached to a message.
 */
@Component
class ListInlineButtonsTool(
    private val telegramClient: TelegramClientService,
    private val entityResolver: EntityResolverService,
    private val guardrailService: GuardrailService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {

    private val log = StructuredLogger.forClass<ListInlineButtonsTool>()

    companion object {
        const val TOOL_NAME = "list_inline_buttons"

        @Suppress("JsonStandardCompliance")
        private val INPUT_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "chat_id": { "type": ["string", "number"], "description": "Chat identifier (ID/@username/+phone)" },
            "message_id": { "type": "number", "description": "Target message ID" }
          },
          "required": ["chat_id", "message_id"]
        }
        """.trimIndent()
    }

    override fun definition(): McpSchema.Tool = ToolSupport.definition(
        name = TOOL_NAME,
        description = "List inline-keyboard buttons attached to a Telegram message",
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
            val messageId = (arguments["message_id"] as? Number)?.toLong()
                ?: arguments["message_id"]?.toString()?.toLongOrNull()
                ?: throw InvalidToolInputException("message_id is required and must be a number")

            guardrailService.validateChatAccess(chatId)
            log.withTool(TOOL_NAME).info("Listing inline buttons for message {} in chat {}", messageId, chatId)
            telegramClient.listInlineButtons(chatId, messageId)
        }
}
