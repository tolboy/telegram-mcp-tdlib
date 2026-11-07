package dev.telegrammcp.server.tool.message

import com.fasterxml.jackson.databind.ObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.exception.InvalidToolInputException
import dev.telegrammcp.server.service.AuditService
import dev.telegrammcp.server.service.EntityResolverService
import dev.telegrammcp.server.service.GuardrailService
import dev.telegrammcp.server.service.OperationGuardService
import dev.telegrammcp.server.tool.McpToolHandler
import dev.telegrammcp.server.tool.ToolSupport
import dev.telegrammcp.server.util.StructuredLogger
import io.micrometer.core.instrument.MeterRegistry
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import org.springframework.stereotype.Component

/**
 * MCP tool: **unpin_all_messages** — removes every pin in a chat. Telegram
 * keeps no list of what was pinned, so this is destructive and
 * confirmation-gated.
 */
@Component
class UnpinAllMessagesTool(
    private val telegramClient: TelegramClientService,
    private val entityResolver: EntityResolverService,
    private val guardrailService: GuardrailService,
    private val operationGuardService: OperationGuardService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {

    private val log = StructuredLogger.forClass<UnpinAllMessagesTool>()

    companion object {
        const val TOOL_NAME = "unpin_all_messages"

        @Suppress("JsonStandardCompliance")
        private val INPUT_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "chat_id": {
              "type": ["string", "integer"],
              "description": "Chat identifier: numeric ID, @username, +phone, or the canonical value self"
            },
            "confirmed": {
              "type": "boolean",
              "description": "Set to true to confirm this destructive operation"
            }
          },
          "required": ["chat_id"]
        }
        """.trimIndent()
    }

    override fun definition(): McpSchema.Tool = ToolSupport.definition(
        TOOL_NAME,
        "Unpin every pinned message in a Telegram chat (destructive, requires confirmation)",
        INPUT_SCHEMA,
        objectMapper,
    )

    override fun execute(exchange: McpSyncServerExchange, arguments: Map<String, Any>): McpSchema.CallToolResult =
        ToolSupport.execute(TOOL_NAME, arguments, objectMapper, meterRegistry, log, "Failed to unpin all messages", auditService) {
            operationGuardService.checkPermission(TOOL_NAME, arguments)
            val chatId = entityResolver.resolve(
                arguments["chat_id"] ?: throw InvalidToolInputException("chat_id is required"),
            )
            guardrailService.validateChatAccess(chatId)
            log.withTool(TOOL_NAME).info("Unpinning all messages in chat {}", chatId)
            telegramClient.unpinAllMessages(chatId)
            mapOf("chat_id" to chatId, "unpinned_all" to true)
        }
}
