package dev.telegrammcp.server.tool.meta

import com.fasterxml.jackson.databind.ObjectMapper
import dev.telegrammcp.server.exception.InvalidToolInputException
import dev.telegrammcp.server.service.AntiSpamGuardService
import dev.telegrammcp.server.service.AuditService
import dev.telegrammcp.server.service.OperationGuardService
import dev.telegrammcp.server.tool.McpToolHandler
import dev.telegrammcp.server.tool.ToolSupport
import dev.telegrammcp.server.util.StructuredLogger
import io.micrometer.core.instrument.MeterRegistry
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import org.springframework.stereotype.Component

/**
 * MCP tool: **register_internal_chat**
 *
 * Tells the anti-spam guard that the given chat is an admin/internal context
 * (typically an operator control group). Internal chats get looser rate-limit
 * thresholds, and the periodic anti-spam digest is delivered to them.
 *
 * The tool itself is exempt from anti-spam — the MCP host is expected to
 * call it once per startup after resolving the main chat.
 */
@Component
class RegisterInternalChatTool(
    private val antiSpamGuardService: AntiSpamGuardService,
    private val operationGuardService: OperationGuardService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {

    private val log = StructuredLogger.forClass<RegisterInternalChatTool>()

    companion object {
        const val TOOL_NAME = "register_internal_chat"

        @Suppress("JsonStandardCompliance")
        private val INPUT_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "chat_id": {
              "type": ["string", "number"],
              "description": "Numeric Telegram chat ID to register as internal admin context"
            }
          },
          "required": ["chat_id"]
        }
        """.trimIndent()
    }

    override fun definition(): McpSchema.Tool = ToolSupport.definition(
        TOOL_NAME,
        "Register a Telegram chat as an internal admin context with looser anti-spam thresholds",
        INPUT_SCHEMA,
        objectMapper,
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
        failureMessage = "Failed to register internal chat",
        auditService = auditService,
    ) {
        operationGuardService.checkPermission(TOOL_NAME, arguments)

        val raw = arguments["chat_id"] ?: throw InvalidToolInputException("chat_id is required")
        val chatId = when (raw) {
            is Number -> raw.toLong()
            is String -> raw.trim().toLongOrNull()
                ?: throw InvalidToolInputException("chat_id must be numeric")
            else -> throw InvalidToolInputException("chat_id must be numeric")
        }

        antiSpamGuardService.registerInternalChat(chatId)
        log.withTool(TOOL_NAME).info("Registered chat {} as internal", chatId)

        mapOf(
            "chat_id" to chatId,
            "internal" to true,
            "internal_chat_ids" to antiSpamGuardService.internalChatIds(),
        )
    }
}
