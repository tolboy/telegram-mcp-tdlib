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
 * MCP tool: **get_message_reactions**
 *
 * Returns the list of reactions placed on a message, including which sender
 * placed which emoji.
 */
@Component
class GetMessageReactionsTool(
    private val telegramClient: TelegramClientService,
    private val entityResolver: EntityResolverService,
    private val guardrailService: GuardrailService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {

    private val log = StructuredLogger.forClass<GetMessageReactionsTool>()

    companion object {
        const val TOOL_NAME = "get_message_reactions"
        private const val MAX_LIMIT = 100

        @Suppress("JsonStandardCompliance")
        private val INPUT_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "chat_id": { "type": ["string", "number"], "description": "Chat identifier (ID/@username/+phone)" },
            "message_id": { "type": "number", "description": "Target message ID" },
            "limit": { "type": "number", "description": "Max reactions to return (1-100, default 50)" }
          },
          "required": ["chat_id", "message_id"]
        }
        """.trimIndent()
    }

    override fun definition(): McpSchema.Tool = ToolSupport.definition(
        name = TOOL_NAME,
        description = "List all reactions placed on a specific message, including who placed them",
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
        failureMessage = "Failed to get reactions",
    ) {
            val chatId = entityResolver.resolve(
                arguments["chat_id"] ?: throw InvalidToolInputException("chat_id is required"),
            )
            val messageId = (arguments["message_id"] as? Number)?.toLong()
                ?: arguments["message_id"]?.toString()?.toLongOrNull()
                ?: throw InvalidToolInputException("message_id is required and must be a number")
            val limit = ((arguments["limit"] as? Number)?.toInt() ?: 50).coerceIn(1, MAX_LIMIT)

            guardrailService.validateChatAccess(chatId)
            log.withTool(TOOL_NAME).info("Listing reactions for message {} in chat {}", messageId, chatId)
            telegramClient.getMessageReactions(chatId, messageId, limit)
        }
}
