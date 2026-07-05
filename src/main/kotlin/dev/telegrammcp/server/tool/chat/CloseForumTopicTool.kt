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
 * MCP tool: **close_forum_topic**
 *
 * Closes a topic in a Telegram forum supergroup.
 */
@Component
class CloseForumTopicTool(
    private val telegramClient: TelegramClientService,
    private val entityResolver: EntityResolverService,
    private val guardrailService: GuardrailService,
    private val operationGuardService: OperationGuardService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {

    private val log = StructuredLogger.forClass<CloseForumTopicTool>()

    companion object {
        const val TOOL_NAME = "close_forum_topic"

        @Suppress("JsonStandardCompliance")
        private val INPUT_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "chat_id": {
              "type": ["string", "number"],
              "description": "Forum-enabled supergroup chat identifier (ID/@username/+phone)"
            },
            "message_thread_id": {
              "type": ["string", "number"],
              "description": "Forum topic message thread ID"
            },
            "topic_id": {
              "type": ["string", "number"],
              "description": "Alias for message_thread_id"
            },
            "thread_id": {
              "type": ["string", "number"],
              "description": "Alias for message_thread_id"
            }
          },
          "required": ["chat_id"]
        }
        """.trimIndent()
    }

    override fun definition(): McpSchema.Tool = ToolSupport.definition(
        TOOL_NAME,
        "Close a forum topic in a Telegram supergroup",
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
        failureMessage = "Failed to close forum topic",
        auditService = auditService,
    ) {
        operationGuardService.checkPermission(TOOL_NAME, arguments)

        val chatId = entityResolver.resolve(
            arguments["chat_id"] ?: throw InvalidToolInputException("chat_id is required"),
        )
        val messageThreadId = parseForumTopicThreadId(arguments)

        guardrailService.validateChatAccess(chatId)
        log.withTool(TOOL_NAME).info("Closing forum topic {} in chat {}", messageThreadId, chatId)

        mapOf(
            "chatId" to chatId,
            "messageThreadId" to messageThreadId,
            "closed" to true,
            "updated" to telegramClient.closeForumTopic(chatId, messageThreadId),
        )
    }
}
