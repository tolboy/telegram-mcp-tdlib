package dev.telegrammcp.server.tool.chat

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
 * MCP tool: **create_group**
 *
 * Creates a new basic group with the specified users.
 */
@Component
class CreateGroupTool(
    private val telegramClient: TelegramClientService,
    private val operationGuardService: OperationGuardService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {

    private val log = StructuredLogger.forClass<CreateGroupTool>()

    companion object {
        const val TOOL_NAME = "create_group"

        @Suppress("JsonStandardCompliance")
        private val INPUT_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "title": {
              "type": "string",
              "description": "Group title"
            },
            "user_ids": {
              "type": "array",
              "items": { "type": "number" },
              "description": "List of user IDs to add as initial members"
            }
          },
          "required": ["title", "user_ids"]
        }
        """.trimIndent()
    }

    override fun definition(): McpSchema.Tool =
        dev.telegrammcp.server.tool.ToolSupport.definition(
            TOOL_NAME,
            "Create a new Telegram group with initial members",
            INPUT_SCHEMA,
            objectMapper,
        )

    @Suppress("UNCHECKED_CAST")
    override fun execute(
        exchange: McpSyncServerExchange,
        arguments: Map<String, Any>,
    ): McpSchema.CallToolResult {
        val sample = Timer.start(meterRegistry)

        return try {
            operationGuardService.checkPermission(TOOL_NAME, arguments)

            val title = arguments["title"]?.toString()
                ?: throw InvalidToolInputException("title is required")
            if (title.isBlank()) throw InvalidToolInputException("title must not be blank")

            val userIds = (arguments["user_ids"] as? List<*>)
                ?.mapNotNull { (it as? Number)?.toLong() }
                ?: throw InvalidToolInputException("user_ids is required and must be a list of numbers")

            if (userIds.isEmpty()) throw InvalidToolInputException("user_ids must not be empty")

            log.withTool(TOOL_NAME).info("Creating group '{}' with {} members", title, userIds.size)

            val chatInfo = telegramClient.createBasicGroup(title, userIds)
            val json = objectMapper.writeValueAsString(chatInfo)

            auditService.record(TOOL_NAME, arguments, AuditOutcome.SUCCESS)
            dev.telegrammcp.server.tool.ToolSupport.textResult(json)
        } catch (ex: Exception) {
            log.withTool(TOOL_NAME).error("Failed to create group: {}", ex.message, ex)
            auditService.record(TOOL_NAME, arguments, AuditOutcome.ERROR, error = ex.message)
            dev.telegrammcp.server.tool.ToolSupport.errorText("Error: ${ex.message}")
        } finally {
            sample.stop(Timer.builder("mcp.tool.execution").tag("tool", TOOL_NAME).register(meterRegistry))
        }
    }
}
