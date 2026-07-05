package dev.telegrammcp.server.tool.chat

import com.fasterxml.jackson.databind.ObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.exception.InvalidToolInputException
import dev.telegrammcp.server.model.AuditOutcome
import dev.telegrammcp.server.service.AuditService
import dev.telegrammcp.server.service.EntityResolverService
import dev.telegrammcp.server.service.GuardrailService
import dev.telegrammcp.server.tool.McpToolHandler
import dev.telegrammcp.server.util.StructuredLogger
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import org.springframework.stereotype.Component

/**
 * MCP tool: **get_recent_actions** — returns the chat event log (admin actions) for a supergroup/channel.
 */
@Component
class GetRecentActionsTool(
    private val telegramClient: TelegramClientService,
    private val entityResolver: EntityResolverService,
    private val guardrailService: GuardrailService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {

    private val log = StructuredLogger.forClass<GetRecentActionsTool>()

    companion object {
        const val TOOL_NAME = "get_recent_actions"
        private const val DEFAULT_LIMIT = 50

        @Suppress("JsonStandardCompliance")
        private val INPUT_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "chat_id": { "type": ["string", "number"], "description": "Chat identifier (supergroup/channel)" },
            "limit": { "type": "number", "description": "Max events (1–100, default 50)" }
          },
          "required": ["chat_id"]
        }
        """.trimIndent()
    }

    override fun definition(): McpSchema.Tool =
        dev.telegrammcp.server.tool.ToolSupport.definition(TOOL_NAME, "Get the chat event log (admin actions) for a supergroup or channel", INPUT_SCHEMA, objectMapper)

    override fun execute(
        exchange: McpSyncServerExchange,
        arguments: Map<String, Any>,
    ): McpSchema.CallToolResult {
        val sample = Timer.start(meterRegistry)

        return try {
            val chatId = entityResolver.resolve(
                arguments["chat_id"] ?: throw InvalidToolInputException("chat_id is required"),
            )
            val limit = (arguments["limit"] as? Number)?.toInt()
                ?: arguments["limit"]?.toString()?.toIntOrNull()
                ?: DEFAULT_LIMIT

            guardrailService.validateChatAccess(chatId)

            log.withTool(TOOL_NAME).info("Fetching event log for chat {} (limit={})", chatId, limit)
            val events = telegramClient.getChatEventLog(chatId, limit)
            auditService.record(TOOL_NAME, arguments, AuditOutcome.SUCCESS)

            val json = objectMapper.writeValueAsString(events)
            dev.telegrammcp.server.tool.ToolSupport.textResult(json)
        } catch (ex: Exception) {
            log.withTool(TOOL_NAME).error("Failed to get event log: {}", ex.message, ex)
            auditService.record(TOOL_NAME, arguments, AuditOutcome.ERROR, error = ex.message)
            dev.telegrammcp.server.tool.ToolSupport.errorText("Error: ${ex.message}")
        } finally {
            sample.stop(Timer.builder("mcp.tool.execution").tag("tool", TOOL_NAME).register(meterRegistry))
        }
    }
}
