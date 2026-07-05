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
 * MCP tool: **get_message_viewers** — read receipts for a message.
 *
 * Telegram exposes viewers only in small groups and for recent outgoing
 * messages; elsewhere the list is empty.
 */
@Component
class GetMessageViewersTool(
    private val telegramClient: TelegramClientService,
    private val entityResolver: EntityResolverService,
    private val guardrailService: GuardrailService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {

    private val log = StructuredLogger.forClass<GetMessageViewersTool>()

    companion object {
        const val TOOL_NAME = "get_message_viewers"

        @Suppress("JsonStandardCompliance")
        private val INPUT_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "chat_id": { "type": ["string", "number"], "description": "Chat identifier (ID/@username/+phone)" },
            "message_id": { "type": "number", "description": "Outgoing message ID to inspect" }
          },
          "required": ["chat_id", "message_id"]
        }
        """.trimIndent()
    }

    override fun definition(): McpSchema.Tool = ToolSupport.definition(
        TOOL_NAME,
        "List users who viewed a message where Telegram exposes read receipts (small groups, recent outgoing messages)",
        INPUT_SCHEMA,
        objectMapper,
    )

    override fun execute(exchange: McpSyncServerExchange, arguments: Map<String, Any>): McpSchema.CallToolResult =
        ToolSupport.execute(TOOL_NAME, arguments, objectMapper, meterRegistry, log, "Failed to get message viewers", auditService) {
            val chatId = entityResolver.resolve(
                arguments["chat_id"] ?: throw InvalidToolInputException("chat_id is required"),
            )
            val messageId = (arguments["message_id"] as? Number)?.toLong()
                ?: arguments["message_id"]?.toString()?.toLongOrNull()
                ?: throw InvalidToolInputException("message_id is required and must be a number")

            guardrailService.validateChatAccess(chatId)
            log.withTool(TOOL_NAME).info("Listing viewers of message {} in chat {}", messageId, chatId)
            mapOf(
                "chat_id" to chatId,
                "message_id" to messageId,
                "viewers" to telegramClient.getMessageViewers(chatId, messageId),
            )
        }
}
