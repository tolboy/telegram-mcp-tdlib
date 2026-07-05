package dev.telegrammcp.server.tool.user

import com.fasterxml.jackson.databind.ObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.exception.InvalidToolInputException
import dev.telegrammcp.server.service.AuditService
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
 * MCP tool: **add_contact**
 *
 * Adds a user to the Telegram address book.
 */
@Component
class AddContactTool(
    private val telegramClient: TelegramClientService,
    private val guardrailService: GuardrailService,
    private val operationGuardService: OperationGuardService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {

    private val log = StructuredLogger.forClass<AddContactTool>()

    companion object {
        const val TOOL_NAME = "add_contact"

        @Suppress("JsonStandardCompliance")
        private val INPUT_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "user_id": {
              "type": "number",
              "description": "Telegram user ID"
            },
            "first_name": {
              "type": "string",
              "description": "Contact's first name"
            },
            "last_name": {
              "type": "string",
              "description": "Contact's last name (optional)"
            },
            "phone_number": {
              "type": "string",
              "description": "Contact's phone number (optional)"
            }
          },
          "required": ["user_id", "first_name"]
        }
        """.trimIndent()
    }

    override fun definition(): McpSchema.Tool = ToolSupport.definition(
      TOOL_NAME,
      "Add a user to the Telegram address book",
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
      failureMessage = "Failed to add contact",
      auditService = auditService,
    ) {
            operationGuardService.checkPermission(TOOL_NAME, arguments)

            val userId = (arguments["user_id"] as? Number)?.toLong()
                ?: throw InvalidToolInputException("user_id is required")
            val firstName = arguments["first_name"]?.toString()
                ?: throw InvalidToolInputException("first_name is required")
            val lastName = arguments["last_name"]?.toString()
            val phoneNumber = arguments["phone_number"]?.toString()

            log.withTool(TOOL_NAME).info("Adding contact: {} {} (userId={})", firstName, lastName ?: "", userId)

            guardrailService.validateInput(firstName)
            if (lastName != null) guardrailService.validateInput(lastName)

            telegramClient.addContact(userId, firstName, lastName, phoneNumber)

            mapOf("added" to true, "user_id" to userId)
        }
}
