package dev.telegrammcp.server.tool.research

import com.fasterxml.jackson.databind.ObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.exception.InvalidToolInputException
import dev.telegrammcp.server.model.TelegramMessage
import dev.telegrammcp.server.service.EntityResolverService
import dev.telegrammcp.server.service.GuardrailService
import dev.telegrammcp.server.tool.McpToolHandler
import dev.telegrammcp.server.tool.ToolSupport
import dev.telegrammcp.server.util.StructuredLogger
import io.micrometer.core.instrument.MeterRegistry
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * MCP tool: **export_chat_history** (read-only).
 *
 * Exports a bounded message slice for local post-processing. Targeted search
 * queries run first, followed by history if requested and capacity remains.
 * This is important for supergroups where the
 * latest main-history slice may contain only service messages.
 */
@Component
class ExportChatHistoryTool(
    private val telegramClient: TelegramClientService,
    private val entityResolver: EntityResolverService,
    private val guardrailService: GuardrailService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {

    private val log = StructuredLogger.forClass<ExportChatHistoryTool>()

    companion object {
        const val TOOL_NAME = "export_chat_history"
        private const val DEFAULT_LIMIT = 200
        private const val MAX_LIMIT = 500
        private const val PAGE_LIMIT = 100
        private const val MAX_HISTORY_PAGES = 12
        private const val MAX_QUERY_TERMS = 8
        private const val MAX_QUERY_LENGTH = 128
        private const val MAX_SEARCH_PAGES_PER_TERM = 8

        @Suppress("JsonStandardCompliance")
        private val INPUT_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "chat_id": {
              "type": ["string", "number"],
              "description": "Chat identifier: numeric ID, @username, +phone, or the canonical value self."
            },
            "since": {
              "type": ["string", "number"],
              "description": "Inclusive lower date bound. ISO instant, yyyy-MM-dd, or unix seconds."
            },
            "until": {
              "type": ["string", "number"],
              "description": "Inclusive upper date bound. ISO instant, yyyy-MM-dd, or unix seconds."
            },
            "limit": {
              "type": "number",
              "description": "Max unique messages to return (1-500, default 200)."
            },
            "query_terms": {
              "type": "array",
              "description": "Optional product/intent search terms used to supplement history export.",
              "items": { "type": "string" }
            },
            "include_history": {
              "type": "boolean",
              "description": "Whether to supplement query results with normal get_history pagination (default true)."
            }
          },
          "required": ["chat_id"]
        }
        """.trimIndent()
    }

    override fun definition(): McpSchema.Tool = ToolSupport.definition(
        name = TOOL_NAME,
        description = "Read-only bounded export of Telegram chat messages by date, with optional query-term search fanout.",
        inputSchema = INPUT_SCHEMA,
        objectMapper = objectMapper,
        dataSchema = dev.telegrammcp.server.tool.ToolOutputSchemas.export,
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
        failureMessage = "Failed to export chat history",
    ) {
        val since = parseInstant(arguments["since"], "since")
        val until = parseInstant(arguments["until"], "until")
        if (since != null && until != null && since.isAfter(until)) {
            throw InvalidToolInputException("since must be before until")
        }
        val chatId = resolveChatId(arguments)
        val limit = extractLimit(arguments)
        val includeHistory = arguments["include_history"]?.toString()?.toBooleanStrictOrNull() ?: true
        val queryTerms = extractQueryTerms(arguments)
        if (!includeHistory && queryTerms.isEmpty()) {
            throw InvalidToolInputException("Enable include_history or provide query_terms")
        }

        guardrailService.validateChatAccess(chatId)
        queryTerms.forEach(guardrailService::validateInput)

        log.withTool(TOOL_NAME).info(
            "Exporting chat {} (limit={}, since={}, until={}, query_terms={}, include_history={})",
            chatId, limit, since, until, queryTerms.size, includeHistory,
        )

        val deduped = linkedMapOf<Long, TelegramMessage>()
        val searches = queryTerms.map { term ->
            collectPages(since, until, limit, MAX_SEARCH_PAGES_PER_TERM, deduped) { cursor ->
                telegramClient.searchMessages(chatId, term, cursor, PAGE_LIMIT)
            }
        }
        val history = if (includeHistory) {
            collectPages(since, until, limit, MAX_HISTORY_PAGES, deduped) { cursor ->
                telegramClient.getHistory(chatId, cursor, 0, PAGE_LIMIT)
            }
        } else null
        val sources = searches + listOfNotNull(history)
        val partialReasons = sources.mapNotNull { it.partialReason }.toMutableSet()
        if (deduped.size > limit) partialReasons.add("result_limit")

        val messages = deduped.values
            .sortedByDescending { it.date }
            .take(limit)

        linkedMapOf(
            "chat_id" to chatId,
            "since" to since?.toString(),
            "until" to until?.toString(),
            "limit" to limit,
            "query_terms" to queryTerms,
            "history_messages" to (history?.scannedCount ?: 0),
            "search_messages" to searches.sumOf { it.scannedCount },
            "total" to messages.size,
            "truncated" to partialReasons.isNotEmpty(),
            "complete" to partialReasons.isEmpty(),
            "completion_scope" to "requested_sources",
            "partial_reasons" to partialReasons.toList(),
            "scanned_count" to sources.sumOf { it.scannedCount },
            "messages" to messages,
        )
    }

    private fun resolveChatId(args: Map<String, Any>): Long {
        val raw = args["chat_id"] ?: throw InvalidToolInputException("chat_id is required")
        return entityResolver.resolve(raw)
    }

    private fun extractLimit(args: Map<String, Any>): Int {
        val raw = args["limit"] ?: return DEFAULT_LIMIT
        val limit = when (raw) {
            is Number -> raw.toInt()
            is String -> raw.toIntOrNull()
                ?: throw InvalidToolInputException("limit must be a valid integer")
            else -> throw InvalidToolInputException("limit must be a number")
        }
        return limit.coerceIn(1, MAX_LIMIT)
    }

    private fun extractQueryTerms(args: Map<String, Any>): List<String> {
        val raw = args["query_terms"] as? List<*> ?: args["queries"] as? List<*> ?: return emptyList()
        return raw.asSequence()
            .mapNotNull { it?.toString()?.trim() }
            .filter { it.isNotBlank() }
            .map { it.take(MAX_QUERY_LENGTH) }
            .distinctBy { it.lowercase() }
            .take(MAX_QUERY_TERMS)
            .toList()
    }

    private fun parseInstant(raw: Any?, fieldName: String): Instant? {
        if (raw == null) return null
        if (raw is Number) return Instant.ofEpochSecond(raw.toLong())
        val text = raw.toString().trim()
        if (text.isBlank()) return null
        text.toLongOrNull()?.let { return Instant.ofEpochSecond(it) }
        return runCatching { Instant.parse(text) }
            .recoverCatching { parseLocalDateBound(text, fieldName) }
            .getOrElse {
                throw InvalidToolInputException("$fieldName must be ISO instant, yyyy-MM-dd, or unix seconds")
            }
    }

    private fun parseLocalDateBound(text: String, fieldName: String): Instant {
        val date = LocalDate.parse(text)
        return if (fieldName == "until") {
            date.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).minusNanos(1)
        } else {
            date.atStartOfDay().toInstant(ZoneOffset.UTC)
        }
    }

    private data class ScanOutcome(val scannedCount: Int, val partialReason: String? = null)

    /** A short TDLib page does not prove exhaustion. Only an empty page or
     * crossing the lower date bound proves completion of this source.
     * Limits and non-advancing cursors remain explicitly incomplete.
     */
    private fun collectPages(
        since: Instant?,
        until: Instant?,
        limit: Int,
        maxPages: Int,
        out: LinkedHashMap<Long, TelegramMessage>,
        fetch: (Long) -> List<TelegramMessage>,
    ): ScanOutcome {
        var fetched = 0
        var cursor = 0L
        repeat(maxPages) {
            if (out.size >= limit) return ScanOutcome(fetched, "result_limit")
            val batch = fetch(cursor)
            if (batch.isEmpty()) return ScanOutcome(fetched)
            fetched += batch.size
            batch.filterInWindow(since, until).forEach { out.putIfAbsent(it.messageId, it) }
            if (since != null && batch.all { it.date.isBefore(since) }) return ScanOutcome(fetched)
            val nextCursor = batch.asSequence().map { it.messageId }.filter { it > 0L }.minOrNull()
            if (nextCursor == null || (cursor != 0L && nextCursor >= cursor)) {
                return ScanOutcome(fetched, "cursor_stalled")
            }
            cursor = nextCursor
        }
        return ScanOutcome(fetched, if (out.size >= limit) "result_limit" else "page_limit")
    }

    private fun List<TelegramMessage>.filterInWindow(since: Instant?, until: Instant?): List<TelegramMessage> =
        filter { msg ->
            (since == null || !msg.date.isBefore(since)) &&
                (until == null || !msg.date.isAfter(until))
        }
}
