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
 * Exports a bounded message slice for local post-processing. The tool first
 * tries normal chat history pagination and can then supplement it with targeted
 * `search_messages` queries, which is important for supergroups where the
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
              "description": "Whether to try normal get_history pagination before search terms (default true)."
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

        guardrailService.validateChatAccess(chatId)
        queryTerms.forEach(guardrailService::validateInput)

        log.withTool(TOOL_NAME).info(
            "Exporting chat {} (limit={}, since={}, until={}, query_terms={}, include_history={})",
            chatId, limit, since, until, queryTerms.size, includeHistory,
        )

        val deduped = linkedMapOf<Long, TelegramMessage>()
        var historyMessages = 0
        var searchMessages = 0

        if (queryTerms.isNotEmpty()) {
            searchMessages = collectSearchHits(chatId, since, until, limit, queryTerms, deduped)
        }
        if (includeHistory && deduped.size < limit) {
            historyMessages = collectHistory(chatId, since, until, limit, deduped)
        }

        val messages = deduped.values
            .sortedByDescending { it.date }
            .take(limit)

        linkedMapOf(
            "chat_id" to chatId,
            "since" to since?.toString(),
            "until" to until?.toString(),
            "limit" to limit,
            "query_terms" to queryTerms,
            "history_messages" to historyMessages,
            "search_messages" to searchMessages,
            "total" to messages.size,
            "truncated" to (deduped.size > limit),
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

    private fun collectHistory(
        chatId: Long,
        since: Instant?,
        until: Instant?,
        limit: Int,
        out: LinkedHashMap<Long, TelegramMessage>,
    ): Int {
        var fetched = 0
        var fromMessageId = 0L
        var guard = 0
        while (out.size < limit && guard++ < 12) {
            val batch = telegramClient.getHistory(chatId, fromMessageId, 0, PAGE_LIMIT)
            if (batch.isEmpty()) break
            fetched += batch.size
            batch.filterInWindow(since, until).forEach { out.putIfAbsent(it.messageId, it) }
            val nextFrom = batch.map { it.messageId }.filter { it > 0L }.minOrNull() ?: break
            if (nextFrom == fromMessageId) break
            fromMessageId = nextFrom
            if (since != null && batch.all { it.date.isBefore(since) }) break
            if (batch.size < PAGE_LIMIT) break
        }
        return fetched
    }

    private fun collectSearchHits(
        chatId: Long,
        since: Instant?,
        until: Instant?,
        limit: Int,
        terms: List<String>,
        out: LinkedHashMap<Long, TelegramMessage>,
    ): Int {
        var fetched = 0
        for (term in terms) {
            var offset = 0L
            var page = 0
            while (out.size < limit && page++ < MAX_SEARCH_PAGES_PER_TERM) {
                val batch = telegramClient.searchMessages(chatId, term, offset, PAGE_LIMIT)
                if (batch.isEmpty()) break
                fetched += batch.size
                batch.filterInWindow(since, until).forEach { out.putIfAbsent(it.messageId, it) }
                val nextOffset = batch.map { it.messageId }.filter { it > 0L }.minOrNull() ?: break
                if (nextOffset == offset) break
                offset = nextOffset
                if (since != null && batch.all { it.date.isBefore(since) }) break
                if (batch.size < PAGE_LIMIT) break
            }
        }
        return fetched
    }

    private fun List<TelegramMessage>.filterInWindow(since: Instant?, until: Instant?): List<TelegramMessage> =
        filter { msg ->
            (since == null || !msg.date.isBefore(since)) &&
                (until == null || !msg.date.isAfter(until))
        }
}
