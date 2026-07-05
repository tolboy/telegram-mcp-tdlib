package dev.telegrammcp.server.tool.user

import com.fasterxml.jackson.databind.ObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.exception.InvalidToolInputException
import dev.telegrammcp.server.model.AuditOutcome
import dev.telegrammcp.server.service.AuditService
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
 * MCP tool: **update_profile** — updates first name, last name and/or bio.
 */
@Component
class UpdateProfileTool(
    private val telegramClient: TelegramClientService,
    private val guardrailService: GuardrailService,
    private val operationGuardService: OperationGuardService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {

    private val log = StructuredLogger.forClass<UpdateProfileTool>()

    companion object {
        const val TOOL_NAME = "update_profile"
        private const val MAX_NAME = 64
        private const val MAX_BIO = 70

        @Suppress("JsonStandardCompliance")
        private val INPUT_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "first_name": { "type": "string", "description": "New first name (1–64 chars)" },
            "last_name": { "type": "string", "description": "New last name (0–64 chars)" },
            "bio": { "type": "string", "description": "New bio/about text (0–70 chars)" }
          },
          "required": []
        }
        """.trimIndent()
    }

    override fun definition(): McpSchema.Tool =
        dev.telegrammcp.server.tool.ToolSupport.definition(TOOL_NAME, "Update the current user's first/last name and/or bio", INPUT_SCHEMA, objectMapper)

    override fun execute(
        exchange: McpSyncServerExchange,
        arguments: Map<String, Any>,
    ): McpSchema.CallToolResult {
        val sample = Timer.start(meterRegistry)

        return try {
            operationGuardService.checkPermission(TOOL_NAME, arguments)

            val firstName = arguments["first_name"]?.toString()
            val lastName = arguments["last_name"]?.toString()
            val bio = arguments["bio"]?.toString()

            if (firstName == null && lastName == null && bio == null) {
                throw InvalidToolInputException("At least one of first_name, last_name, bio is required")
            }
            firstName?.let {
                if (it.length > MAX_NAME) throw InvalidToolInputException("first_name must be ≤$MAX_NAME chars")
                guardrailService.validateInput(it)
            }
            lastName?.let {
                if (it.length > MAX_NAME) throw InvalidToolInputException("last_name must be ≤$MAX_NAME chars")
                guardrailService.validateInput(it)
            }
            bio?.let {
                if (it.length > MAX_BIO) throw InvalidToolInputException("bio must be ≤$MAX_BIO chars")
                guardrailService.validateInput(it)
            }

            log.withTool(TOOL_NAME).info("Updating profile")
            telegramClient.updateProfile(firstName, lastName, bio)
            auditService.record(TOOL_NAME, arguments, AuditOutcome.SUCCESS)

            val json = objectMapper.writeValueAsString(mapOf("updated" to true))
            dev.telegrammcp.server.tool.ToolSupport.textResult(json)
        } catch (ex: Exception) {
            log.withTool(TOOL_NAME).error("Failed to update profile: {}", ex.message, ex)
            auditService.record(TOOL_NAME, arguments, AuditOutcome.ERROR, error = ex.message)
            dev.telegrammcp.server.tool.ToolSupport.errorText("Error: ${ex.message}")
        } finally {
            sample.stop(Timer.builder("mcp.tool.execution").tag("tool", TOOL_NAME).register(meterRegistry))
        }
    }
}
