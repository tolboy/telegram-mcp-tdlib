package dev.telegrammcp.server.tool.draft

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
 * MCP tool: **save_draft** — persists a draft message on a chat.
 */
@Component
class SaveDraftTool(
    private val telegramClient: TelegramClientService,
    private val entityResolver: EntityResolverService,
    private val guardrailService: GuardrailService,
    private val operationGuardService: OperationGuardService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {

    private val log = StructuredLogger.forClass<SaveDraftTool>()

    companion object {
        const val TOOL_NAME = "save_draft"

        @Suppress("JsonStandardCompliance")
        private val INPUT_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "chat_id": { "type": ["string", "number"], "description": "Chat identifier" },
            "text": { "type": "string", "description": "Draft text" },
            "reply_to_message_id": { "type": "number", "description": "Optional message being replied to" }
          },
          "required": ["chat_id", "text"]
        }
        """.trimIndent()
    }

    override fun definition(): McpSchema.Tool = ToolSupport.definition(
        TOOL_NAME,
        "Save a draft message on a chat (optionally as a reply)",
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
        failureMessage = "Failed to save draft",
        auditService = auditService,
    ) {
            operationGuardService.checkPermission(TOOL_NAME, arguments)

            val chatId = entityResolver.resolve(
                arguments["chat_id"] ?: throw InvalidToolInputException("chat_id is required"),
            )
            val text = arguments["text"]?.toString()
                ?: throw InvalidToolInputException("text is required")
            val replyTo = (arguments["reply_to_message_id"] as? Number)?.toLong()
                ?: arguments["reply_to_message_id"]?.toString()?.toLongOrNull()

            guardrailService.validateChatAccess(chatId)
            guardrailService.validateInput(text)

            log.withTool(TOOL_NAME).info("Saving draft on chat {} (text length={})", chatId, text.length)
            telegramClient.saveDraft(chatId, text, replyTo)

            mapOf("chat_id" to chatId, "saved" to true)
    }
}
