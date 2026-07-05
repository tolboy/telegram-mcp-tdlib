package dev.telegrammcp.server.tool.message

import com.fasterxml.jackson.databind.ObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.exception.InvalidToolInputException
import dev.telegrammcp.server.service.AuditService
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
 * MCP tool: **get_message_link** — creates a shareable t.me link for a message.
 */
@Component
class GetMessageLinkTool(
    private val telegramClient: TelegramClientService,
    private val entityResolver: EntityResolverService,
    private val guardrailService: GuardrailService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {

    private val log = StructuredLogger.forClass<GetMessageLinkTool>()

    companion object {
        const val TOOL_NAME = "get_message_link"

        @Suppress("JsonStandardCompliance")
        private val INPUT_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "chat_id": { "type": ["string", "number"], "description": "Chat identifier (ID/@username/+phone)" },
            "message_id": { "type": "number", "description": "Message ID to link" },
            "for_album": { "type": "boolean", "description": "Link the whole media album when available (default false)" },
            "in_message_thread": { "type": "boolean", "description": "Keep the link inside the current message thread (default false)" }
          },
          "required": ["chat_id", "message_id"]
        }
        """.trimIndent()
    }

    override fun definition(): McpSchema.Tool = ToolSupport.definition(
        name = TOOL_NAME,
        description = "Create a shareable Telegram link for a message without modifying it",
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
        failureMessage = "Failed to create message link",
        auditService = auditService,
    ) {
        val chatId = entityResolver.resolve(
            arguments["chat_id"] ?: throw InvalidToolInputException("chat_id is required"),
        )
        val messageId = (arguments["message_id"] as? Number)?.toLong()
            ?: arguments["message_id"]?.toString()?.toLongOrNull()
            ?: throw InvalidToolInputException("message_id is required and must be a number")
        val forAlbum = arguments["for_album"].toBooleanArgument("for_album")
        val inMessageThread = arguments["in_message_thread"].toBooleanArgument("in_message_thread")

        guardrailService.validateChatAccess(chatId)
        log.withTool(TOOL_NAME).info("Creating link for message {} in chat {}", messageId, chatId)
        telegramClient.getMessageLink(chatId, messageId, forAlbum, inMessageThread)
    }

    private fun Any?.toBooleanArgument(name: String): Boolean = when (this) {
        null -> false
        is Boolean -> this
        is String -> when (lowercase()) {
            "true" -> true
            "false" -> false
            else -> throw InvalidToolInputException("$name must be a boolean")
        }
        else -> throw InvalidToolInputException("$name must be a boolean")
    }
}
