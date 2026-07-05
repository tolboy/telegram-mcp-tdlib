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
 * MCP tool: **set_slow_mode** — sets a supergroup's slow-mode delay.
 *
 * Group-impacting: every non-admin member becomes limited to one message per
 * delay window, so the tool is confirmation-gated like other permission writes.
 */
@Component
class SetSlowModeTool(
    private val telegramClient: TelegramClientService,
    private val entityResolver: EntityResolverService,
    private val guardrailService: GuardrailService,
    private val operationGuardService: OperationGuardService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {

    private val log = StructuredLogger.forClass<SetSlowModeTool>()

    companion object {
        const val TOOL_NAME = "set_slow_mode"

        /** The delays Telegram accepts; anything else is rejected server-side. */
        private val ALLOWED_DELAYS = setOf(0, 10, 30, 60, 300, 900, 3600)

        @Suppress("JsonStandardCompliance")
        private val INPUT_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "chat_id": { "type": ["string", "number"], "description": "Supergroup identifier (ID/@username)" },
            "delay_seconds": {
              "type": "number",
              "description": "Seconds between messages per member: 0 (off), 10, 30, 60, 300, 900, or 3600"
            },
            "confirmed": { "type": "boolean", "description": "Required when confirmation mode is enabled" }
          },
          "required": ["chat_id", "delay_seconds"]
        }
        """.trimIndent()
    }

    override fun definition(): McpSchema.Tool = ToolSupport.definition(
        TOOL_NAME,
        "Set the slow-mode delay of a Telegram supergroup (0 disables slow mode)",
        INPUT_SCHEMA,
        objectMapper,
    )

    override fun execute(exchange: McpSyncServerExchange, arguments: Map<String, Any>): McpSchema.CallToolResult =
        ToolSupport.execute(TOOL_NAME, arguments, objectMapper, meterRegistry, log, "Failed to set slow mode", auditService) {
            operationGuardService.checkPermission(TOOL_NAME, arguments)

            val chatId = entityResolver.resolve(
                arguments["chat_id"] ?: throw InvalidToolInputException("chat_id is required"),
            )
            val delay = (arguments["delay_seconds"] as? Number)?.toInt()
                ?: arguments["delay_seconds"]?.toString()?.toIntOrNull()
                ?: throw InvalidToolInputException("delay_seconds is required and must be a number")
            if (delay !in ALLOWED_DELAYS) {
                throw InvalidToolInputException(
                    "delay_seconds must be one of ${ALLOWED_DELAYS.sorted().joinToString()}",
                )
            }

            guardrailService.validateChatAccess(chatId)
            log.withTool(TOOL_NAME).info("Setting slow mode of chat {} to {}s", chatId, delay)
            telegramClient.setChatSlowModeDelay(chatId, delay)
            mapOf("chat_id" to chatId, "delay_seconds" to delay, "updated" to true)
        }
}
