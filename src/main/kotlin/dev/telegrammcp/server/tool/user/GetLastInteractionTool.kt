package dev.telegrammcp.server.tool.user

import com.fasterxml.jackson.databind.ObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.exception.InvalidToolInputException
import dev.telegrammcp.server.service.AuditService
import dev.telegrammcp.server.service.EntityResolverService
import dev.telegrammcp.server.service.GuardrailService
import dev.telegrammcp.server.tool.McpToolHandler
import dev.telegrammcp.server.tool.ToolSupport
import dev.telegrammcp.server.util.StructuredLogger
import io.micrometer.core.instrument.MeterRegistry
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import org.springframework.stereotype.Component

/**
 * MCP tool: **get_last_interaction** — returns the most recent message exchanged with a contact.
 */
@Component
class GetLastInteractionTool(
    private val telegramClient: TelegramClientService,
    private val entityResolver: EntityResolverService,
    private val guardrailService: GuardrailService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {

    private val log = StructuredLogger.forClass<GetLastInteractionTool>()

    companion object {
        const val TOOL_NAME = "get_last_interaction"

        @Suppress("JsonStandardCompliance")
        private val INPUT_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "contact_id": { "type": ["string", "number"], "description": "Contact identifier (ID/@username/+phone)" }
          },
          "required": ["contact_id"]
        }
        """.trimIndent()
    }

    override fun definition(): McpSchema.Tool = ToolSupport.definition(
        name = TOOL_NAME,
        description = "Get the most recent message exchanged with a contact",
        inputSchema = INPUT_SCHEMA,
        objectMapper = objectMapper,
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
        failureMessage = "Failed to get last interaction",
        auditService = auditService,
    ) {
            val contactId = entityResolver.resolve(
                arguments["contact_id"] ?: throw InvalidToolInputException("contact_id is required"),
            )

            log.withTool(TOOL_NAME).info("Fetching last interaction with contact {}", contactId)
            val message = telegramClient.getLastInteractionWithContact(contactId)
            message?.let { guardrailService.validateChatAccess(it.chatId) }
            message ?: mapOf("contact_id" to contactId, "message" to null)
        }
}
