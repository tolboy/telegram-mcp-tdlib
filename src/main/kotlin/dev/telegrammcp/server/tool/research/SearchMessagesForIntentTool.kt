package dev.telegrammcp.server.tool.research

import com.fasterxml.jackson.databind.ObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.config.PublicSearchProperties
import dev.telegrammcp.server.exception.InvalidToolInputException
import dev.telegrammcp.server.model.TelegramMessage
import dev.telegrammcp.server.service.AuditService
import dev.telegrammcp.server.service.EntityResolverService
import dev.telegrammcp.server.service.GuardrailService
import dev.telegrammcp.server.tool.McpToolHandler
import dev.telegrammcp.server.tool.ToolSupport
import dev.telegrammcp.server.util.StructuredLogger
import io.micrometer.core.instrument.MeterRegistry
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.Locale

/**
 * MCP tool: **search_public_messages** (read-only).
 *
 * Fans out [TelegramClientService.searchMessages] across a list of public chats
 * and returns flattened hits. Scoring or workflow decisions intentionally live
 * outside this standalone connector.
 *
 * The per-chat message cap is clamped to [PublicSearchProperties.LimitsProps.maxMessagesPerChat].
 */
@Component
class SearchMessagesForIntentTool(
    private val telegramClient: TelegramClientService,
    private val entityResolver: EntityResolverService,
    private val publicSearchProps: PublicSearchProperties,
    private val guardrailService: GuardrailService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {

    private val log = StructuredLogger.forClass<SearchMessagesForIntentTool>()

    companion object {
        const val TOOL_NAME = "search_public_messages"
        private const val MAX_CHATS = 25
        private const val MAX_QUERY_LENGTH = 256
        private const val MAX_QUERY_VARIANTS = 8

        @Suppress("JsonStandardCompliance")
        private val INPUT_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "chats": {
              "type": "array",
              "description": "Chat identifiers (numeric IDs or @usernames).",
              "items": { "type": ["string", "number"] }
            },
            "query": { "type": "string", "description": "Primary search query in any language." },
            "query_variants": {
              "type": "array",
              "description": "Optional equivalent spellings, synonyms, or translations supplied by the caller; each is searched independently.",
              "items": { "type": "string" }
            },
            "limit_per_chat": { "type": "number", "description": "Max messages per chat (clamped by public-search config)." }
          },
          "required": ["chats", "query"]
        }
        """.trimIndent()
    }

    override fun definition(): McpSchema.Tool = ToolSupport.definition(
        name = TOOL_NAME,
        description = "Read-only search across multiple chats using a primary query and optional caller-supplied multilingual variants.",
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
        failureMessage = "Failed to search public messages",
        auditService = auditService,
    ) {
        if (!publicSearchProps.enabled) {
            throw InvalidToolInputException("Public search is disabled (public-search.enabled=false)")
        }
        val rawChats = arguments["chats"] as? List<*>
            ?: throw InvalidToolInputException("chats must be a JSON array")
        if (rawChats.isEmpty()) throw InvalidToolInputException("chats must not be empty")
        if (rawChats.size > MAX_CHATS) {
            throw InvalidToolInputException("chats exceeds maximum of $MAX_CHATS")
        }
        val query = arguments["query"]?.toString()?.takeIf { it.isNotBlank() }
            ?: throw InvalidToolInputException("query is required")
        if (query.length > MAX_QUERY_LENGTH) {
            throw InvalidToolInputException("query exceeds maximum length of $MAX_QUERY_LENGTH")
        }
        val requested = (arguments["limit_per_chat"] as? Number)?.toInt()
            ?: arguments["limit_per_chat"]?.toString()?.toIntOrNull()
            ?: publicSearchProps.limits.maxMessagesPerChat
        val limit = requested.coerceIn(1, publicSearchProps.limits.maxMessagesPerChat)

        guardrailService.validateInput(query)
        val searchQueries = parseQueryVariants(query, arguments["query_variants"])
        searchQueries.forEach(guardrailService::validateInput)
        meterRegistry.counter("telegram.mcp.public_search.queries_total").increment(searchQueries.size.toDouble())
        log.withTool(TOOL_NAME).info(
            "Searching '{}' across {} chats (limit_per_chat={}, query_variants={})",
            query,
            rawChats.size,
            limit,
            searchQueries.size,
        )

        // Phase 5: fan out per-chat searches in parallel under a bounded
        // semaphore, with a wall-clock timeout so a single slow chat cannot
        // block the entire tool call. Partial results are preserved on timeout.
        val fanout = publicSearchProps.fanout
        val chatSemaphore = Semaphore(fanout.maxConcurrentChats.coerceAtLeast(1))
        val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
        val completedResults = ConcurrentHashMap<Int, Map<String, Any>>()
        val results: List<Map<String, Any>> = runBlocking {
            try {
                withTimeout(fanout.toolCallTimeoutMs) {
                    coroutineScope {
                        rawChats.mapIndexed { idx, raw ->
                            async(Dispatchers.IO) {
                                chatSemaphore.withPermit {
                                    searchChat(raw, searchQueries, limit)?.let { completedResults[idx] = it }
                                }
                            }
                        }.awaitAll().filterNotNull()
                    }
                }
            } catch (ex: TimeoutCancellationException) {
                timedOut.set(true)
                log.withTool(TOOL_NAME).warn(
                    "Tool call timed out after {}ms (chats={}, query_variants={})",
                    fanout.toolCallTimeoutMs, rawChats.size, searchQueries.size,
                )
            }
            completedResults.toSortedMap().values.toList()
        }

        val payload = linkedMapOf<String, Any>(
            "query" to query,
            "search_queries" to searchQueries,
            "limit_per_chat" to limit,
            "chats" to results,
        )
        if (timedOut.get()) {
            payload["timed_out"] = true
            payload["timeout_ms"] = fanout.toolCallTimeoutMs
        }
        payload
    }

    /**
     * Resolve a single chat and run the parallel per-query fan-out inside it.
     * Returns `null` only when the input value itself is null. Resolution and
     * search failures are surfaced as an `error` entry so the model sees that
     * the chat was attempted.
     */
    private suspend fun searchChat(
        raw: Any?,
        expandedQueries: List<String>,
        limit: Int,
    ): Map<String, Any>? {
        val candidate = raw ?: return null
        val chatId = try {
            withContext(Dispatchers.IO) { entityResolver.resolve(candidate) }
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            log.withTool(TOOL_NAME).warn("Skipping unresolved chat '{}': {}", candidate, ex.message)
            return mapOf(
                "chat" to candidate.toString(),
                "error" to (ex.message ?: "resolution failed"),
                "messages" to emptyList<Any>(),
            )
        }
        return try {
            guardrailService.validateChatAccess(chatId)
            val messages = searchExpandedMessages(chatId, expandedQueries, limit)
            mapOf(
                "chat" to candidate.toString(),
                "chat_id" to chatId,
                "messages" to messages,
            )
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            log.withTool(TOOL_NAME).warn("Search failed in chat {}: {}", chatId, ex.message)
            mapOf(
                "chat" to candidate.toString(),
                "chat_id" to chatId,
                "error" to (ex.message ?: "search failed"),
                "messages" to emptyList<Any>(),
            )
        }
    }

    /**
     * Fan out caller-supplied query variants inside one chat. Per-query results
     * are deduplicated by [TelegramMessage.dedupeKey] and reported through
     * low-cardinality aggregate metrics.
     */
    private suspend fun searchExpandedMessages(
        chatId: Long,
        queries: List<String>,
        limit: Int,
    ): List<TelegramMessage> = coroutineScope {
        val fanout = publicSearchProps.fanout
        val querySemaphore = Semaphore(fanout.maxConcurrentQueriesPerChat.coerceAtLeast(1))
        val perQueryHits: List<List<TelegramMessage>> = queries.mapIndexed { idx, q ->
            async(Dispatchers.IO) {
                querySemaphore.withPermit {
                    try {
                        val hits = telegramClient.searchMessages(chatId, q, 0L, limit)
                        meterRegistry.counter("telegram.mcp.public_search.hits_total").increment(hits.size.toDouble())
                        hits
                    } catch (ex: CancellationException) {
                        throw ex
                    } catch (ex: Exception) {
                        log.withTool(TOOL_NAME).debug(
                            "Per-query search failed (chat={}, query='{}'): {}", chatId, q, ex.message,
                        )
                        emptyList()
                    }
                }
            }
        }.awaitAll()

        val deduped = linkedMapOf<String, TelegramMessage>()
        perQueryHits.forEach { hits ->
            hits.forEach { message -> deduped.putIfAbsent(message.dedupeKey(), message) }
        }
        deduped.values.take(limit)
    }

    private fun TelegramMessage.dedupeKey(): String =
        if (messageId > 0) {
            "$chatId:$messageId"
        } else {
            listOf(chatId.toString(), senderId?.toString().orEmpty(), date.toString(), text.orEmpty().take(160))
                .joinToString("\u001f")
        }

    private fun parseQueryVariants(primaryQuery: String, rawVariants: Any?): List<String> {
        val variants: List<*> = rawVariants as? List<*> ?: emptyList<Any?>()
        if (rawVariants != null && rawVariants !is List<*>) {
            throw InvalidToolInputException("query_variants must be a JSON array")
        }
        return sequenceOf(primaryQuery)
            .plus(
                variants.asSequence()
                    .mapNotNull { it?.toString()?.trim() }
                    .filter { it.isNotBlank() }
                    .onEach {
                        if (it.length > MAX_QUERY_LENGTH) {
                            throw InvalidToolInputException("query variants must be $MAX_QUERY_LENGTH characters or fewer")
                        }
                    }
                    .take(MAX_QUERY_VARIANTS - 1),
            )
            .distinctBy { it.lowercase(Locale.ROOT) }
            .take(MAX_QUERY_VARIANTS)
            .toList()
    }
}
