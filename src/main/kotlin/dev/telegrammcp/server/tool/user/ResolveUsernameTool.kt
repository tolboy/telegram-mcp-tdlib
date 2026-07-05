package dev.telegrammcp.server.tool.user

import com.fasterxml.jackson.databind.ObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.exception.InvalidToolInputException
import dev.telegrammcp.server.service.AuditService
import dev.telegrammcp.server.service.EntityResolverService
import dev.telegrammcp.server.model.AuditOutcome
import dev.telegrammcp.server.tool.McpToolHandler
import dev.telegrammcp.server.util.StructuredLogger
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import org.springframework.stereotype.Component

/**
 * MCP tool: **resolve_username**
 *
 * Resolves a Telegram @username, phone number, or numeric ID to a detailed
 * entity with the resolved numeric ID and user/chat information.
 *
 * This is the key tool for converting human-readable identifiers to immutable
 * Telegram numeric IDs.
 *
 * ### Input schema
 * ```json
 * {
 *   "identifier": "@username"
 * }
 * ```
 */
@Component
class ResolveUsernameTool(
    private val telegramClient: TelegramClientService,
    private val entityResolver: EntityResolverService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {

    private val log = StructuredLogger.forClass<ResolveUsernameTool>()

    companion object {
        const val TOOL_NAME = "resolve_username"

        @Suppress("JsonStandardCompliance")
        private val INPUT_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "identifier": {
              "type": ["string", "number"],
              "description": "Entity to resolve: @username, +phone number, or numeric ID"
            }
          },
          "required": ["identifier"]
        }
        """.trimIndent()
    }

    override fun definition(): McpSchema.Tool =
        dev.telegrammcp.server.tool.ToolSupport.definition(TOOL_NAME, "Resolve a Telegram @username, +phone, or numeric ID to an entity with details", INPUT_SCHEMA, objectMapper)

    override fun execute(
        exchange: McpSyncServerExchange,
        arguments: Map<String, Any>,
    ): McpSchema.CallToolResult {
        val sample = Timer.start(meterRegistry)
        val startMs = System.currentTimeMillis()

        return try {
            val raw = arguments["identifier"]
                ?: throw InvalidToolInputException("identifier is required")

            log.withTool(TOOL_NAME).info("Resolving identifier: {}", raw)

            val resolvedId = entityResolver.resolve(raw)

            // Try to get user info if it's a user ID (positive), otherwise chat info
            val result: Any = if (resolvedId > 0) {
                try {
                    telegramClient.getUser(resolvedId)
                } catch (_: Exception) {
                    // Might be a chat, not a user
                    try {
                        telegramClient.getChat(resolvedId)
                    } catch (_: Exception) {
                        mapOf("id" to resolvedId, "type" to "unknown")
                    }
                }
            } else {
                try {
                    telegramClient.getChat(resolvedId)
                } catch (_: Exception) {
                    mapOf("id" to resolvedId, "type" to "unknown")
                }
            }

            val json = objectMapper.writeValueAsString(result)

            auditService.record(TOOL_NAME, arguments, AuditOutcome.SUCCESS, durationMs = System.currentTimeMillis() - startMs)
            dev.telegrammcp.server.tool.ToolSupport.textResult(json)
        } catch (ex: Exception) {
            log.withTool(TOOL_NAME).error("Failed to resolve identifier: {}", ex.message, ex)
            auditService.record(TOOL_NAME, arguments, AuditOutcome.ERROR, error = ex.message)
            dev.telegrammcp.server.tool.ToolSupport.errorText("Error: ${ex.message}")
        } finally {
            sample.stop(Timer.builder("mcp.tool.execution").tag("tool", TOOL_NAME).register(meterRegistry))
        }
    }
}
