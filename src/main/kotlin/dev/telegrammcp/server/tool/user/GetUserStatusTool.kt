package dev.telegrammcp.server.tool.user

import com.fasterxml.jackson.databind.ObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.exception.InvalidToolInputException
import dev.telegrammcp.server.model.AuditOutcome
import dev.telegrammcp.server.service.AuditService
import dev.telegrammcp.server.service.EntityResolverService
import dev.telegrammcp.server.tool.McpToolHandler
import dev.telegrammcp.server.util.StructuredLogger
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import org.springframework.stereotype.Component

/**
 * MCP tool: **get_user_status** — returns the online/last-seen status of a user.
 */
@Component
class GetUserStatusTool(
    private val telegramClient: TelegramClientService,
    private val entityResolver: EntityResolverService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {

    private val log = StructuredLogger.forClass<GetUserStatusTool>()

    companion object {
        const val TOOL_NAME = "get_user_status"

        @Suppress("JsonStandardCompliance")
        private val INPUT_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "user_id": { "type": ["string", "number"], "description": "User identifier (ID/@username/+phone)" }
          },
          "required": ["user_id"]
        }
        """.trimIndent()
    }

    override fun definition(): McpSchema.Tool =
        dev.telegrammcp.server.tool.ToolSupport.definition(TOOL_NAME, "Get the online/last-seen status of a Telegram user", INPUT_SCHEMA, objectMapper)

    override fun execute(
        exchange: McpSyncServerExchange,
        arguments: Map<String, Any>,
    ): McpSchema.CallToolResult {
        val sample = Timer.start(meterRegistry)

        return try {
            val userId = entityResolver.resolve(
                arguments["user_id"] ?: throw InvalidToolInputException("user_id is required"),
            )

            log.withTool(TOOL_NAME).info("Getting status for user {}", userId)
            val status = telegramClient.getUserStatus(userId)
            auditService.record(TOOL_NAME, arguments, AuditOutcome.SUCCESS)

            val json = objectMapper.writeValueAsString(mapOf("user_id" to userId, "status" to status))
            dev.telegrammcp.server.tool.ToolSupport.textResult(json)
        } catch (ex: Exception) {
            log.withTool(TOOL_NAME).error("Failed to get user status: {}", ex.message, ex)
            auditService.record(TOOL_NAME, arguments, AuditOutcome.ERROR, error = ex.message)
            dev.telegrammcp.server.tool.ToolSupport.errorText("Error: ${ex.message}")
        } finally {
            sample.stop(Timer.builder("mcp.tool.execution").tag("tool", TOOL_NAME).register(meterRegistry))
        }
    }
}
