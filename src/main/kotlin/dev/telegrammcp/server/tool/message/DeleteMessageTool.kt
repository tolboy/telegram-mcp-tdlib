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
 * MCP tool: **delete_message**
 *
 * Deletes one or more messages from a chat. This is a destructive operation
 * and requires confirmation when confirmation mode is enabled.
 */
@Component
class DeleteMessageTool(
    private val telegramClient: TelegramClientService,
    private val entityResolver: EntityResolverService,
    private val guardrailService: GuardrailService,
    private val operationGuardService: OperationGuardService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {

    private val log = StructuredLogger.forClass<DeleteMessageTool>()

    companion object {
        const val TOOL_NAME = "delete_message"

        @Suppress("JsonStandardCompliance")
        private val INPUT_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "chat_id": {
              "type": ["string", "number"],
              "description": "Chat identifier: numeric ID, @username, +phone, or the canonical value self"
            },
            "message_ids": {
              "type": "array",
              "items": { "type": "number" },
              "description": "List of message IDs to delete"
            },
            "revoke": {
              "type": "boolean",
              "description": "Delete for all participants (default: true)"
            },
            "confirmed": {
              "type": "boolean",
              "description": "Set to true to confirm this destructive operation"
            }
          },
          "required": ["chat_id", "message_ids"]
        }
        """.trimIndent()
    }

    override fun definition(): McpSchema.Tool =
        dev.telegrammcp.server.tool.ToolSupport.definition(TOOL_NAME, "Delete one or more messages from a Telegram chat (destructive, requires confirmation)", INPUT_SCHEMA, objectMapper)

    @Suppress("UNCHECKED_CAST")
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
            val messageIds = (arguments["message_ids"] as? List<*>)
                ?.mapNotNull { (it as? Number)?.toLong() }
                ?: throw InvalidToolInputException("message_ids is required and must be a list of numbers")
            val revoke = arguments["revoke"]?.toString()?.toBoolean() ?: true

            if (messageIds.isEmpty()) throw InvalidToolInputException("message_ids must not be empty")

            log.withTool(TOOL_NAME).info("Deleting {} messages from chat {} (revoke={})", messageIds.size, chatId, revoke)

            guardrailService.validateChatAccess(chatId)
            telegramClient.deleteMessages(chatId, messageIds, revoke)

            auditService.record(TOOL_NAME, arguments, AuditOutcome.SUCCESS)
            val json = objectMapper.writeValueAsString(mapOf("deleted" to messageIds.size, "chat_id" to chatId))
            dev.telegrammcp.server.tool.ToolSupport.textResult(json)
        } catch (ex: Exception) {
            log.withTool(TOOL_NAME).error("Failed to delete messages: {}", ex.message, ex)
            auditService.record(TOOL_NAME, arguments, AuditOutcome.ERROR, error = ex.message)
            dev.telegrammcp.server.tool.ToolSupport.errorText("Error: ${ex.message}")
        } finally {
            sample.stop(Timer.builder("mcp.tool.execution").tag("tool", TOOL_NAME).register(meterRegistry))
        }
    }
}
