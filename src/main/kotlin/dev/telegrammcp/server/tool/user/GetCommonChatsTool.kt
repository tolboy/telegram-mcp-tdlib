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
 * MCP tool: **get_common_chats** — groups and channels shared with a user.
 */
@Component
class GetCommonChatsTool(
    private val telegramClient: TelegramClientService,
    private val entityResolver: EntityResolverService,
    private val guardrailService: GuardrailService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {

    private val log = StructuredLogger.forClass<GetCommonChatsTool>()

    companion object {
        const val TOOL_NAME = "get_common_chats"

        @Suppress("JsonStandardCompliance")
        private val INPUT_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "user_id": { "type": ["string", "number"], "description": "User identifier (ID/@username/+phone)" },
            "limit": { "type": "number", "description": "Max chats to return (default 50, max 100)" }
          },
          "required": ["user_id"]
        }
        """.trimIndent()
    }

    override fun definition(): McpSchema.Tool = ToolSupport.definition(
        TOOL_NAME,
        "List groups and channels the current account shares with a user",
        INPUT_SCHEMA,
        objectMapper,
    )

    override fun execute(exchange: McpSyncServerExchange, arguments: Map<String, Any>): McpSchema.CallToolResult =
        ToolSupport.execute(TOOL_NAME, arguments, objectMapper, meterRegistry, log, "Failed to get common chats", auditService) {
            val userId = entityResolver.resolve(
                arguments["user_id"] ?: throw InvalidToolInputException("user_id is required"),
            )
            val limit = ((arguments["limit"] as? Number)?.toInt() ?: 50).coerceIn(1, 100)

            log.withTool(TOOL_NAME).info("Listing chats in common with user {} (limit={})", userId, limit)
            val chats = telegramClient.getGroupsInCommon(userId, limit)
                .filter { guardrailService.isChatAllowed(it.chatId) }
            mapOf("user_id" to userId, "chats" to chats)
        }
}
