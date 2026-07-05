package dev.telegrammcp.server.tool.chat

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
 * MCP tool: **set_chat_description** — replaces the about/description text of
 * a group, supergroup, or channel.
 */
@Component
class SetChatDescriptionTool(
    private val telegramClient: TelegramClientService,
    private val entityResolver: EntityResolverService,
    private val guardrailService: GuardrailService,
    private val operationGuardService: OperationGuardService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {

    private val log = StructuredLogger.forClass<SetChatDescriptionTool>()

    companion object {
        const val TOOL_NAME = "set_chat_description"
        private const val MAX_DESCRIPTION = 255

        @Suppress("JsonStandardCompliance")
        private val INPUT_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "chat_id": { "type": ["string", "number"], "description": "Chat identifier (ID/@username)" },
            "description": { "type": "string", "description": "New description (max 255 chars; empty string clears it)" }
          },
          "required": ["chat_id", "description"]
        }
        """.trimIndent()
    }

    override fun definition(): McpSchema.Tool = ToolSupport.definition(
        TOOL_NAME,
        "Replace the description of a Telegram group, supergroup, or channel",
        INPUT_SCHEMA,
        objectMapper,
    )

    override fun execute(exchange: McpSyncServerExchange, arguments: Map<String, Any>): McpSchema.CallToolResult =
        ToolSupport.execute(TOOL_NAME, arguments, objectMapper, meterRegistry, log, "Failed to set chat description", auditService) {
            operationGuardService.checkPermission(TOOL_NAME, arguments)

            val chatId = entityResolver.resolve(
                arguments["chat_id"] ?: throw InvalidToolInputException("chat_id is required"),
            )
            val description = arguments["description"]?.toString()
                ?: throw InvalidToolInputException("description is required (use an empty string to clear)")
            if (description.length > MAX_DESCRIPTION) {
                throw InvalidToolInputException("description must be $MAX_DESCRIPTION characters or fewer")
            }

            guardrailService.validateInput(description)
            guardrailService.validateChatAccess(chatId)
            log.withTool(TOOL_NAME).info("Setting description of chat {} ({} chars)", chatId, description.length)
            telegramClient.setChatDescription(chatId, description)
            mapOf("chat_id" to chatId, "description" to description, "updated" to true)
        }
}
