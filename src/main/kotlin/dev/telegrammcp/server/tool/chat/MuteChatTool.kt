package dev.telegrammcp.server.tool.chat

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
 * MCP tool: **mute_chat**
 *
 * Mutes notifications for a chat.
 */
@Component
class MuteChatTool(
    private val telegramClient: TelegramClientService,
    private val entityResolver: EntityResolverService,
    private val guardrailService: GuardrailService,
    private val operationGuardService: OperationGuardService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {

    private val log = StructuredLogger.forClass<MuteChatTool>()

    companion object {
        const val TOOL_NAME = "mute_chat"

        @Suppress("JsonStandardCompliance")
        private val INPUT_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "chat_id": {
              "type": ["string", "number"],
              "description": "Chat identifier to mute"
            },
            "duration": {
              "type": "number",
              "description": "Mute duration in seconds (0 = forever, default: forever)"
            }
          },
          "required": ["chat_id"]
        }
        """.trimIndent()
    }

    override fun definition(): McpSchema.Tool =
        dev.telegrammcp.server.tool.ToolSupport.definition(TOOL_NAME, "Mute notifications for a Telegram chat", INPUT_SCHEMA, objectMapper)

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
            val duration = (arguments["duration"] as? Number)?.toInt() ?: Int.MAX_VALUE

            log.withTool(TOOL_NAME).info("Muting chat {} for {} seconds", chatId, duration)
            guardrailService.validateChatAccess(chatId)
            telegramClient.muteChat(chatId, duration)

            auditService.record(TOOL_NAME, arguments, AuditOutcome.SUCCESS)
            val json = objectMapper.writeValueAsString(mapOf("muted" to true, "chat_id" to chatId))
            dev.telegrammcp.server.tool.ToolSupport.textResult(json)
        } catch (ex: Exception) {
            log.withTool(TOOL_NAME).error("Failed to mute chat: {}", ex.message, ex)
            auditService.record(TOOL_NAME, arguments, AuditOutcome.ERROR, error = ex.message)
            dev.telegrammcp.server.tool.ToolSupport.errorText("Error: ${ex.message}")
        } finally {
            sample.stop(Timer.builder("mcp.tool.execution").tag("tool", TOOL_NAME).register(meterRegistry))
        }
    }
}
