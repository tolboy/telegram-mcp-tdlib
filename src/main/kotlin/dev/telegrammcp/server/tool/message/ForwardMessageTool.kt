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
 * MCP tool: **forward_message**
 *
 * Forwards one or more messages from one chat to another.
 */
@Component
class ForwardMessageTool(
    private val telegramClient: TelegramClientService,
    private val entityResolver: EntityResolverService,
    private val guardrailService: GuardrailService,
    private val operationGuardService: OperationGuardService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {

    private val log = StructuredLogger.forClass<ForwardMessageTool>()

    companion object {
        const val TOOL_NAME = "forward_message"

        @Suppress("JsonStandardCompliance")
        private val INPUT_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "from_chat_id": {
              "type": ["string", "number"],
              "description": "Source chat identifier"
            },
            "to_chat_id": {
              "type": ["string", "number"],
              "description": "Destination chat identifier"
            },
            "message_ids": {
              "type": "array",
              "items": { "type": "number" },
              "description": "List of message IDs to forward"
            }
          },
          "required": ["from_chat_id", "to_chat_id", "message_ids"]
        }
        """.trimIndent()
    }

    override fun definition(): McpSchema.Tool =
        dev.telegrammcp.server.tool.ToolSupport.definition(TOOL_NAME, "Forward messages from one Telegram chat to another", INPUT_SCHEMA, objectMapper)

    @Suppress("UNCHECKED_CAST")
    override fun execute(
        exchange: McpSyncServerExchange,
        arguments: Map<String, Any>,
    ): McpSchema.CallToolResult {
        val sample = Timer.start(meterRegistry)

        return try {
            operationGuardService.checkPermission(TOOL_NAME, arguments)

            val fromChatId = entityResolver.resolve(
                arguments["from_chat_id"] ?: throw InvalidToolInputException("from_chat_id is required"),
            )
            val toChatId = entityResolver.resolve(
                arguments["to_chat_id"] ?: throw InvalidToolInputException("to_chat_id is required"),
            )
            val messageIds = (arguments["message_ids"] as? List<*>)
                ?.mapNotNull { (it as? Number)?.toLong() }
                ?: throw InvalidToolInputException("message_ids is required and must be a list of numbers")

            if (messageIds.isEmpty()) throw InvalidToolInputException("message_ids must not be empty")

            log.withTool(TOOL_NAME).info("Forwarding {} messages from chat {} to chat {}", messageIds.size, fromChatId, toChatId)

            guardrailService.validateChatAccess(fromChatId)
            guardrailService.validateChatAccess(toChatId)

            val forwarded = telegramClient.forwardMessages(fromChatId, toChatId, messageIds)
            val json = objectMapper.writeValueAsString(forwarded)

            auditService.record(TOOL_NAME, arguments, AuditOutcome.SUCCESS)
            dev.telegrammcp.server.tool.ToolSupport.textResult(json)
        } catch (ex: Exception) {
            log.withTool(TOOL_NAME).error("Failed to forward messages: {}", ex.message, ex)
            auditService.record(TOOL_NAME, arguments, AuditOutcome.ERROR, error = ex.message)
            dev.telegrammcp.server.tool.ToolSupport.errorText("Error: ${ex.message}")
        } finally {
            sample.stop(Timer.builder("mcp.tool.execution").tag("tool", TOOL_NAME).register(meterRegistry))
        }
    }
}
