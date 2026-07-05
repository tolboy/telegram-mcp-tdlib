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
 * MCP tool: **set_forum_topics_enabled**
 *
 * Enables or disables Telegram forum topics in a supergroup.
 */
@Component
class SetForumTopicsEnabledTool(
    private val telegramClient: TelegramClientService,
    private val entityResolver: EntityResolverService,
    private val guardrailService: GuardrailService,
    private val operationGuardService: OperationGuardService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {

    private val log = StructuredLogger.forClass<SetForumTopicsEnabledTool>()

    companion object {
        const val TOOL_NAME = "set_forum_topics_enabled"

        @Suppress("JsonStandardCompliance")
        private val INPUT_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "chat_id": {
              "type": ["string", "number"],
              "description": "Supergroup chat identifier (ID/@username/+phone)"
            },
            "enabled": {
              "type": "boolean",
              "description": "Whether forum topics must be enabled (default true)"
            },
            "has_forum_tabs": {
              "type": "boolean",
              "description": "Whether Telegram should show forum tabs when enabling topics (default true)"
            }
          },
          "required": ["chat_id"]
        }
        """.trimIndent()
    }

    override fun definition(): McpSchema.Tool = ToolSupport.definition(
        TOOL_NAME,
        "Enable or disable forum topics in a Telegram supergroup",
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
        failureMessage = "Failed to update forum topics setting",
        auditService = auditService,
    ) {
        operationGuardService.checkPermission(TOOL_NAME, arguments)

        val chatId = entityResolver.resolve(
            arguments["chat_id"] ?: throw InvalidToolInputException("chat_id is required"),
        )
        val enabled = arguments["enabled"]?.toString()?.toBooleanStrictOrNull() ?: true
        val hasForumTabs = arguments["has_forum_tabs"]?.toString()?.toBooleanStrictOrNull() ?: true

        guardrailService.validateChatAccess(chatId)
        log.withTool(TOOL_NAME).info(
            "Setting forum topics enabled={} hasForumTabs={} in chat {}",
            enabled,
            hasForumTabs,
            chatId,
        )

        mapOf(
            "chatId" to chatId,
            "enabled" to enabled,
            "updated" to telegramClient.setForumTopicsEnabled(chatId, enabled, hasForumTabs),
        )
    }
}
