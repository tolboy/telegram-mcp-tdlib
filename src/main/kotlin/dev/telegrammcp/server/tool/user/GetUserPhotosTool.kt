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
 * MCP tool: **get_user_photos** — lists profile photos for a user.
 */
@Component
class GetUserPhotosTool(
    private val telegramClient: TelegramClientService,
    private val entityResolver: EntityResolverService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {

    private val log = StructuredLogger.forClass<GetUserPhotosTool>()

    companion object {
        const val TOOL_NAME = "get_user_photos"
        private const val DEFAULT_LIMIT = 20

        @Suppress("JsonStandardCompliance")
        private val INPUT_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "user_id": { "type": ["string", "number"], "description": "User identifier (ID/@username/+phone)" },
            "limit": { "type": "number", "description": "Max photos (1–100, default 20)" }
          },
          "required": ["user_id"]
        }
        """.trimIndent()
    }

    override fun definition(): McpSchema.Tool =
        dev.telegrammcp.server.tool.ToolSupport.definition(TOOL_NAME, "Get profile photos of a user", INPUT_SCHEMA, objectMapper)

    override fun execute(
        exchange: McpSyncServerExchange,
        arguments: Map<String, Any>,
    ): McpSchema.CallToolResult {
        val sample = Timer.start(meterRegistry)

        return try {
            val userId = entityResolver.resolve(
                arguments["user_id"] ?: throw InvalidToolInputException("user_id is required"),
            )
            val limit = (arguments["limit"] as? Number)?.toInt()
                ?: arguments["limit"]?.toString()?.toIntOrNull()
                ?: DEFAULT_LIMIT

            log.withTool(TOOL_NAME).info("Getting profile photos for user {} (limit={})", userId, limit)
            val photos = telegramClient.getUserProfilePhotos(userId, limit)
            auditService.record(TOOL_NAME, arguments, AuditOutcome.SUCCESS)

            val json = objectMapper.writeValueAsString(photos)
            dev.telegrammcp.server.tool.ToolSupport.textResult(json)
        } catch (ex: Exception) {
            log.withTool(TOOL_NAME).error("Failed to get user photos: {}", ex.message, ex)
            auditService.record(TOOL_NAME, arguments, AuditOutcome.ERROR, error = ex.message)
            dev.telegrammcp.server.tool.ToolSupport.errorText("Error: ${ex.message}")
        } finally {
            sample.stop(Timer.builder("mcp.tool.execution").tag("tool", TOOL_NAME).register(meterRegistry))
        }
    }
}
