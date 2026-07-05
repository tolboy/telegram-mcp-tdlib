package dev.telegrammcp.server.tool.user

import com.fasterxml.jackson.databind.ObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.exception.InvalidToolInputException
import dev.telegrammcp.server.model.AuditOutcome
import dev.telegrammcp.server.service.AuditService
import dev.telegrammcp.server.service.OperationGuardService
import dev.telegrammcp.server.tool.McpToolHandler
import dev.telegrammcp.server.util.StructuredLogger
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import org.springframework.stereotype.Component

/**
 * MCP tool: **block_user**
 *
 * Blocks a user. Destructive, requires confirmation.
 */
@Component
class BlockUserTool(
    private val telegramClient: TelegramClientService,
    private val operationGuardService: OperationGuardService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {

    private val log = StructuredLogger.forClass<BlockUserTool>()

    companion object {
        const val TOOL_NAME = "block_user"

        @Suppress("JsonStandardCompliance")
        private val INPUT_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "user_id": {
              "type": "number",
              "description": "Telegram user ID to block"
            },
            "confirmed": {
              "type": "boolean",
              "description": "Set to true to confirm this destructive operation"
            }
          },
          "required": ["user_id"]
        }
        """.trimIndent()
    }

    override fun definition(): McpSchema.Tool =
        dev.telegrammcp.server.tool.ToolSupport.definition(TOOL_NAME, "Block a Telegram user (destructive, requires confirmation)", INPUT_SCHEMA, objectMapper)

    override fun execute(
        exchange: McpSyncServerExchange,
        arguments: Map<String, Any>,
    ): McpSchema.CallToolResult {
        val sample = Timer.start(meterRegistry)

        return try {
            operationGuardService.checkPermission(TOOL_NAME, arguments)

            val userId = (arguments["user_id"] as? Number)?.toLong()
                ?: throw InvalidToolInputException("user_id is required")

            log.withTool(TOOL_NAME).info("Blocking user {}", userId)

            telegramClient.blockUser(userId)

            auditService.record(TOOL_NAME, arguments, AuditOutcome.SUCCESS)
            val json = objectMapper.writeValueAsString(mapOf("blocked" to true, "user_id" to userId))
            dev.telegrammcp.server.tool.ToolSupport.textResult(json)
        } catch (ex: Exception) {
            log.withTool(TOOL_NAME).error("Failed to block user: {}", ex.message, ex)
            auditService.record(TOOL_NAME, arguments, AuditOutcome.ERROR, error = ex.message)
            dev.telegrammcp.server.tool.ToolSupport.errorText("Error: ${ex.message}")
        } finally {
            sample.stop(Timer.builder("mcp.tool.execution").tag("tool", TOOL_NAME).register(meterRegistry))
        }
    }
}
