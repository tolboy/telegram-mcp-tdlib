package dev.telegrammcp.server.tool.user

import com.fasterxml.jackson.databind.ObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.service.AuditService
import dev.telegrammcp.server.model.AuditOutcome
import dev.telegrammcp.server.tool.McpToolHandler
import dev.telegrammcp.server.util.StructuredLogger
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import org.springframework.stereotype.Component

/**
 * MCP tool: **list_contacts**
 *
 * Returns the list of contacts in the user's Telegram address book.
 * Optional query parameter for client-side filtering by name/username.
 *
 * ### Input schema
 * ```json
 * {
 *   "query": "John"
 * }
 * ```
 */
@Component
class ListContactsTool(
    private val telegramClient: TelegramClientService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {

    private val log = StructuredLogger.forClass<ListContactsTool>()

    companion object {
        const val TOOL_NAME = "list_contacts"

        @Suppress("JsonStandardCompliance")
        private val INPUT_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "query": {
              "type": "string",
              "description": "Optional filter by name or username (case-insensitive substring match)"
            }
          },
          "required": []
        }
        """.trimIndent()
    }

    override fun definition(): McpSchema.Tool =
        dev.telegrammcp.server.tool.ToolSupport.definition(TOOL_NAME, "List contacts from the Telegram address book with optional name/username filter", INPUT_SCHEMA, objectMapper)

    override fun execute(
        exchange: McpSyncServerExchange,
        arguments: Map<String, Any>,
    ): McpSchema.CallToolResult {
        val sample = Timer.start(meterRegistry)
        val startMs = System.currentTimeMillis()

        return try {
            val query = arguments["query"]?.toString()?.trim()

            log.withTool(TOOL_NAME).info("Listing contacts (query='{}')", query ?: "")

            var contacts = telegramClient.getContacts()

            // Client-side filtering if query is provided
            if (!query.isNullOrBlank()) {
                contacts = contacts.filter { contact ->
                    val searchable = listOfNotNull(
                        contact.firstName,
                        contact.lastName,
                        contact.username?.let { "@$it" },
                    ).joinToString(" ").lowercase()
                    searchable.contains(query.lowercase())
                }
            }

            val json = objectMapper.writeValueAsString(contacts)

            auditService.record(TOOL_NAME, arguments, AuditOutcome.SUCCESS, durationMs = System.currentTimeMillis() - startMs)
            dev.telegrammcp.server.tool.ToolSupport.textResult(json)
        } catch (ex: Exception) {
            log.withTool(TOOL_NAME).error("Failed to list contacts: {}", ex.message, ex)
            auditService.record(TOOL_NAME, arguments, AuditOutcome.ERROR, error = ex.message)
            dev.telegrammcp.server.tool.ToolSupport.errorText("Error: ${ex.message}")
        } finally {
            sample.stop(Timer.builder("mcp.tool.execution").tag("tool", TOOL_NAME).register(meterRegistry))
        }
    }
}
