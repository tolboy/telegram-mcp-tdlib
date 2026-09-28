package dev.telegrammcp.server.tool.task

import com.fasterxml.jackson.databind.ObjectMapper
import dev.telegrammcp.server.client.TelegramAccountContext
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.model.TelegramMessage
import dev.telegrammcp.server.service.*
import dev.telegrammcp.server.tool.*
import dev.telegrammcp.server.util.StructuredLogger
import io.micrometer.core.instrument.MeterRegistry
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import org.springframework.stereotype.Component

data class CompactChat(val chatId: Long, val title: String?, val unread: Int)
data class CompactMessage(val messageId: Long, val sender: String, val text: String?, val media: String?, val contentTruncated: Boolean)
data class InboxSnapshot(val chats: List<CompactChat>, val scanned: Int, val truncated: Boolean,
    val scope: String = "bounded_recent_chats", val budgetUnit: String = "normalized_json_characters")
data class ConversationBundle(val chatId: Long, val messages: List<CompactMessage>, val nextMessageId: Long?,
    val truncated: Boolean, val scope: String = "bounded_history", val budgetUnit: String = "normalized_json_characters")

internal fun Map<String, Any>.integer(name: String, default: Int, min: Int, max: Int): Int {
    val value = this[name]?.toString()?.toIntOrNull() ?: if (containsKey(name)) throw IllegalArgumentException("$name must be an integer") else default
    require(value in min..max) { "$name must be in $min..$max" }
    return value
}

/** Budget the actual normalized data JSON, including metadata and escaping. */
internal fun jsonSize(value: Any, mapper: ObjectMapper) =
    mapper.writeValueAsString(UntrustedContentNormalizer.normalize(value, mapper).value).length

abstract class TaskTool(private val name: String, private val description: String, private val schema: String,
    protected val mapper: ObjectMapper, private val metrics: MeterRegistry) : McpToolHandler {
    override fun definition() = ToolSupport.definition(name, description, schema, mapper)
    override fun execute(exchange: McpSyncServerExchange, arguments: Map<String, Any>): McpSchema.CallToolResult =
        ToolSupport.execute(name, arguments, mapper, metrics, StructuredLogger.forClass<TaskTool>(), "Task tool failed") { run(arguments) }
    abstract fun run(args: Map<String, Any>): Any
}

@Component
class InboxSnapshotTool(private val client: TelegramClientService, private val guard: GuardrailService,
    mapper: ObjectMapper, metrics: MeterRegistry) : TaskTool(TOOL_NAME,
    "Unread inbox snapshot with bounded normalized JSON output; no read receipts are changed.",
    """{"type":"object","properties":{"limit":{"type":"integer","minimum":1,"maximum":200},"max_chars":{"type":"integer","minimum":512,"maximum":32000}}}""", mapper, metrics) {
    companion object { const val TOOL_NAME = "inbox_snapshot" }
    override fun run(args: Map<String, Any>): InboxSnapshot {
        val limit = args.integer("limit", 50, 1, 200)
        val budget = args.integer("max_chars", 8000, 512, 32000)
        val source = client.getChats(limit)
        val selected = source.filter { guard.isChatAllowed(it.chatId) && (it.unreadCount ?: 0) > 0 }
        val rows = mutableListOf<CompactChat>()
        var result = InboxSnapshot(rows.toList(), source.size, true)
        for (chat in selected) {
            val candidate = CompactChat(chat.chatId, chat.title?.take(160), chat.unreadCount ?: 0)
            val next = result.copy(chats = rows + candidate)
            if (jsonSize(next, mapper) > budget) break
            rows += candidate
            result = next
        }
        // getChats is a bounded prefix, never proof of full inbox exhaustion.
        return result
    }
}

@Component
class ConversationBundleTool(private val client: TelegramClientService, private val resolver: EntityResolverService,
    private val guard: GuardrailService, mapper: ObjectMapper, metrics: MeterRegistry) : TaskTool(TOOL_NAME,
    "Compact conversation page with stable message IDs; pass nextMessageId as from_message_id for an exclusive older-than cursor.",
    """{"type":"object","properties":{"chat_id":{"type":["string","number"]},"limit":{"type":"integer","minimum":1,"maximum":100},"from_message_id":{"type":"integer","minimum":0},"max_chars":{"type":"integer","minimum":512,"maximum":32000}},"required":["chat_id"]}""", mapper, metrics) {
    companion object { const val TOOL_NAME = "conversation_bundle" }
    override fun run(args: Map<String, Any>): ConversationBundle {
        val chat = resolver.resolve(requireNotNull(args["chat_id"]) { "chat_id is required" })
        guard.validateChatAccess(chat)
        val limit = args.integer("limit", 20, 1, 100)
        val budget = args.integer("max_chars", 8000, 512, 32000)
        val cursor = args["from_message_id"]?.toString()?.toLongOrNull() ?: if (args.containsKey("from_message_id")) throw IllegalArgumentException("Invalid cursor") else 0L
        require(cursor >= 0)
        val raw = client.getHistory(chat, cursor, 0, limit)
        require(raw.all { it.chatId == chat && it.messageId > 0 }) { "History returned an unexpected chat or message ID" }
        val rows = mutableListOf<CompactMessage>()
        var result = ConversationBundle(chat, emptyList(), cursor.takeIf { it > 0 }, raw.isNotEmpty())
        for (message in raw.filter { cursor == 0L || it.messageId < cursor }.distinctBy { it.messageId }.sortedByDescending(TelegramMessage::messageId)) {
            var text = message.text?.take(500)
            var candidate = CompactMessage(message.messageId, message.senderName.take(80), text, message.mediaType?.take(40),
                text != message.text || message.senderName.length > 80 || (message.mediaType?.length ?: 0) > 40)
            var next = result.copy(messages = rows + candidate, nextMessageId = message.messageId)
            while (jsonSize(next, mapper) > budget && !text.isNullOrEmpty()) {
                text = text.take(text.length / 2)
                candidate = candidate.copy(text = text, contentTruncated = true)
                next = next.copy(messages = rows + candidate)
            }
            if (jsonSize(next, mapper) > budget) break
            rows += candidate
            result = next
        }
        return result
    }
}

@Component
class ChangesSinceTool(private val journal: ChangeJournal, private val accounts: TelegramAccountContext,
    private val guard: GuardrailService, mapper: ObjectMapper, metrics: MeterRegistry) : TaskTool(TOOL_NAME,
    "Page through persistent observed message changes. Gaps require history reconciliation; not a Telegram-wide changelog.",
    """{"type":"object","properties":{"cursor":{"type":"string"},"limit":{"type":"integer","minimum":1,"maximum":200},"max_chars":{"type":"integer","minimum":512,"maximum":32000}}}""", mapper, metrics) {
    companion object { const val TOOL_NAME = "changes_since" }
    override fun run(args: Map<String, Any>): ChangePage {
        var limit = args.integer("limit", 100, 1, 200)
        val budget = args.integer("max_chars", 8000, 512, 32000)
        while (true) {
            val page = journal.page(accounts.currentAccount(), args["cursor"]?.toString(), limit, guard::isChatAllowed)
            if (jsonSize(page, mapper) <= budget) return page
            require(limit > 1) { "Response exceeds budget" }
            limit = (limit / 2).coerceAtLeast(1)
        }
    }
}

@Component
class ExportJobTool(private val jobs: ExportJobService, private val resolver: EntityResolverService,
    mapper: ObjectMapper, metrics: MeterRegistry) : TaskTool(TOOL_NAME,
    "Persistent pull-driven export: start, resume one page, status, read page, cancel or delete. Jobs are scoped to account and client.",
    """{"type":"object","properties":{"action":{"type":"string","enum":["start","resume","status","page","cancel","delete"]},"chat_id":{"type":["string","number"]},"job_id":{"type":"string"},"page":{"type":"integer","minimum":0}},"required":["action"]}""", mapper, metrics) {
    companion object { const val TOOL_NAME = "export_job" }
    override fun run(args: Map<String, Any>): ExportJobResult {
        val action = requireNotNull(args["action"]) { "action is required" }.toString()
        return if (action == "start") jobs.create(resolver.resolve(requireNotNull(args["chat_id"]) { "chat_id is required" }))
        else jobs.operate(requireNotNull(args["job_id"]) { "job_id is required" }.toString(), action,
            if (action == "page") args.integer("page", 0, 0, 9999) else null)
    }
}
