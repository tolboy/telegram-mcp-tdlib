package dev.telegrammcp.server.tool.chat

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
 * MCP tool: **create_channel**
 *
 * Creates a new supergroup or broadcast channel. This is a destructive operation
 * requiring confirmation because it creates a permanent entity owned by the
 * current account.
 */
@Component
class CreateChannelTool(
    private val telegramClient: TelegramClientService,
    private val guardrailService: GuardrailService,
    private val operationGuardService: OperationGuardService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {

    private val log = StructuredLogger.forClass<CreateChannelTool>()

    companion object {
        const val TOOL_NAME = "create_channel"
        private const val MAX_TITLE = 128
        private const val MAX_DESCRIPTION = 255

        @Suppress("JsonStandardCompliance")
        private val INPUT_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "title": {
              "type": "string",
              "description": "Channel or supergroup title (1-128 chars)"
            },
            "description": {
              "type": "string",
              "description": "Description / about text (optional, max 255 chars)"
            },
            "is_supergroup": {
              "type": "boolean",
              "description": "If true creates a supergroup, otherwise a broadcast channel (default false)"
            },
            "is_forum": {
              "type": "boolean",
              "description": "If true creates a forum-enabled supergroup with topics (requires is_supergroup=true)"
            },
            "confirmed": {
              "type": "boolean",
              "description": "Set to true to confirm this operation"
            }
          },
          "required": ["title"]
        }
        """.trimIndent()
    }

    override fun definition(): McpSchema.Tool = ToolSupport.definition(
        TOOL_NAME,
        "Create a new Telegram supergroup, forum-enabled supergroup, or broadcast channel (requires confirmation)",
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
        failureMessage = "Failed to create channel",
        auditService = auditService,
    ) {
        operationGuardService.checkPermission(TOOL_NAME, arguments)

        val title = arguments["title"]?.toString()?.takeIf { it.isNotBlank() }
            ?: throw InvalidToolInputException("title is required")
        if (title.length > MAX_TITLE) {
            throw InvalidToolInputException("title must be $MAX_TITLE characters or fewer")
        }

        val description = arguments["description"]?.toString() ?: ""
        if (description.length > MAX_DESCRIPTION) {
            throw InvalidToolInputException("description must be $MAX_DESCRIPTION characters or fewer")
        }

        val isSupergroup = arguments["is_supergroup"]?.toString()?.toBoolean() ?: false
        val isForum = arguments["is_forum"]?.toString()?.toBoolean() ?: false
        if (isForum && !isSupergroup) {
            throw InvalidToolInputException("is_forum can be true only when is_supergroup is true")
        }

        guardrailService.validateInput(title)
        if (description.isNotBlank()) guardrailService.validateInput(description)

        val kind = if (isForum) "forum supergroup" else if (isSupergroup) "supergroup" else "channel"
        log.withTool(TOOL_NAME).info("Creating {} '{}'", kind, title)

        telegramClient.createSupergroupOrChannel(title, description, isSupergroup, isForum)
    }
}
