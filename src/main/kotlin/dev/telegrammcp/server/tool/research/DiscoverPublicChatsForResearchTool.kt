package dev.telegrammcp.server.tool.research

import com.fasterxml.jackson.databind.ObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.config.PublicSearchProperties
import dev.telegrammcp.server.exception.InvalidToolInputException
import dev.telegrammcp.server.service.AuditService
import dev.telegrammcp.server.service.GuardrailService
import dev.telegrammcp.server.tool.McpToolHandler
import dev.telegrammcp.server.tool.ToolSupport
import dev.telegrammcp.server.util.StructuredLogger
import io.micrometer.core.instrument.MeterRegistry
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import org.springframework.stereotype.Component

/**
 * MCP tool: **discover_public_chats** (read-only).
 *
 * Wraps [TelegramClientService.searchPublicChats] but applies an optional
 * result allowlist when [PublicSearchProperties.chatAllowlist] is non-empty.
 * If an allowlist is configured, results not present in the list are filtered out.
 *
 * This tool is `exempt = true` in the anti-spam guard — it makes no message-rate
 * calls to Telegram beyond the catalog search the user already has access to.
 */
@Component
class DiscoverPublicChatsForResearchTool(
    private val telegramClient: TelegramClientService,
    private val publicSearchProps: PublicSearchProperties,
    private val guardrailService: GuardrailService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {

    private val log = StructuredLogger.forClass<DiscoverPublicChatsForResearchTool>()

    companion object {
        const val TOOL_NAME = "discover_public_chats"

        @Suppress("JsonStandardCompliance")
        private val INPUT_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "query": { "type": "string", "description": "Search query (username prefix or title)" },
            "limit": { "type": "number", "description": "Max results. Clamped by public-search.limits.max-chats-per-search." }
          },
          "required": ["query"]
        }
        """.trimIndent()
    }

    override fun definition(): McpSchema.Tool = ToolSupport.definition(
        name = TOOL_NAME,
        description = "Read-only discovery of public Telegram chats; honors the optional configured chat allowlist.",
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
        failureMessage = "Failed to discover public chats",
        auditService = auditService,
    ) {
        if (!publicSearchProps.enabled) {
            throw InvalidToolInputException("Public search is disabled (public-search.enabled=false)")
        }
        val query = arguments["query"]?.toString()?.takeIf { it.isNotBlank() }
            ?: throw InvalidToolInputException("query is required")
        val limit = (arguments["limit"] as? Number)?.toInt()
            ?: arguments["limit"]?.toString()?.toIntOrNull()
            ?: publicSearchProps.limits.maxChatsPerSearch
        val cappedLimit = limit.coerceIn(1, publicSearchProps.limits.maxChatsPerSearch)

        guardrailService.validateInput(query)
        log.withTool(TOOL_NAME).info("Discovering public chats for '{}', limit={}", query, cappedLimit)

        val raw = telegramClient.searchPublicChats(query, cappedLimit)
        val allowlist = publicSearchProps.chatAllowlist
            .map { it.trimStart('@').lowercase() }
            .toSet()
        val filtered = if (allowlist.isEmpty()) {
            raw
        } else {
            raw.filter { chat ->
                val candidates = listOfNotNull(
                    chat.username?.trimStart('@')?.lowercase(),
                    chat.chatId.toString(),
                )
                candidates.any { it in allowlist }
            }
        }
        mapOf(
            "query" to query,
            "limit" to cappedLimit,
            "allowlist_applied" to allowlist.isNotEmpty(),
            "results" to filtered,
            "filtered_out" to (raw.size - filtered.size),
            // When the allowlist is active and rejected everything, surface the
            // raw candidates' public identifiers so operators can spot config
            // drift (e.g. typo'd username, hyphen vs underscore) without having
            // to disable the gate. Bounded to keep payloads small.
            "filtered_candidates" to if (allowlist.isNotEmpty() && filtered.isEmpty()) {
                raw.take(10).map { chat ->
                    mapOf(
                        "chat_id" to chat.chatId,
                        "title" to chat.title,
                        "username" to chat.username,
                    )
                }
            } else {
                emptyList<Map<String, Any?>>()
            },
        )
    }
}
