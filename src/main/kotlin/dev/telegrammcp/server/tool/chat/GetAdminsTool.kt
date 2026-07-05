package dev.telegrammcp.server.tool.chat

import com.fasterxml.jackson.databind.ObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.exception.InvalidToolInputException
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
 * MCP tool: **get_admins**
 *
 * Lists administrators of a group, supergroup, or channel.
 */
@Component
class GetAdminsTool(
    private val telegramClient: TelegramClientService,
    private val entityResolver: EntityResolverService,
    private val guardrailService: GuardrailService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {

    private val log = StructuredLogger.forClass<GetAdminsTool>()

    companion object {
        const val TOOL_NAME = "get_admins"
        private const val MAX_LIMIT = 200
        private const val DEFAULT_LIMIT = 50

        @Suppress("JsonStandardCompliance")
        private val INPUT_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "chat_id": {
              "type": ["string", "number"],
              "description": "Chat identifier (ID/@username/+phone)"
            },
            "limit": {
              "type": "number",
              "description": "Max admins to return (1-200, default 50)"
            }
          },
          "required": ["chat_id"]
        }
        """.trimIndent()
    }

    override fun definition(): McpSchema.Tool = ToolSupport.definition(
        name = TOOL_NAME,
        description = "List administrators of a Telegram group, supergroup, or channel",
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
        failureMessage = "Failed to list admins",
    ) {
            val chatId = entityResolver.resolve(
                arguments["chat_id"] ?: throw InvalidToolInputException("chat_id is required"),
            )
            val limit = ((arguments["limit"] as? Number)?.toInt() ?: DEFAULT_LIMIT)
                .coerceIn(1, MAX_LIMIT)

            guardrailService.validateChatAccess(chatId)
            log.withTool(TOOL_NAME).info("Listing admins of chat {} (limit={})", chatId, limit)
            telegramClient.getChatAdmins(chatId, limit)
        }
}
