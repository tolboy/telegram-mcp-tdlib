package dev.telegrammcp.server.service

import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.client.ToolCallDeadline
import dev.telegrammcp.server.exception.TelegramApiException
import dev.telegrammcp.server.model.MessageAddressing
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/** One incoming message of a burst. Content is not included; hydrate it with get_messages. */
data class SettledMessage(val chatId: Long, val messageId: Long, val senderId: Long?, val observedAt: String,
    val addressed: Boolean, val addressedBy: List<String>)

/** Per-chat summary of the returned messages. */
data class SettledChat(val chatId: Long, val messageCount: Int, val firstMessageId: Long, val lastMessageId: Long,
    val addressed: Boolean, val firstObservedAt: String, val lastObservedAt: String)

/**
 * Result of [SettledMessageWaiter.await].
 *
 * [status] is `settled` (the watched chats went quiet for the settle window after
 * new incoming messages), `timed_out`, `limit` (the message limit was reached
 * before the chats went quiet), `gap` (coverage broke: catch up from history) or
 * `reset` (a session or journal started over: catch up from history; `gap` is
 * also true). Always keep [nextCursor].
 */
data class SettledWaitResult(
    val status: String,
    val settled: Boolean,
    val messages: List<SettledMessage>,
    val chats: List<SettledChat>,
    val nextCursor: String,
    val hasMore: Boolean,
    val gap: Boolean,
    val reset: Boolean,
    val gapReasons: List<String>,
    val timedOut: Boolean,
    val pendingMessages: Int,
    val settleSeconds: Int,
    val quietMillis: Long?,
    val skippedChanges: Int,
    val scope: String = "locally_observed_updates_only",
)

data class SettleRequest(val cursor: String?, val chatId: Long?, val settle: Duration, val timeout: Duration,
    val limit: Int, val addressedOnly: Boolean) {
    init {
        require(!settle.isNegative && settle <= Duration.ofSeconds(MAX_SETTLE_SECONDS)) { "settle must be in 0..$MAX_SETTLE_SECONDS seconds" }
        require(!timeout.isNegative && timeout <= Duration.ofSeconds(30)) { "timeout must be in 0..30 seconds" }
        require(limit in 1..200) { "limit must be in 1..200" }
    }
    companion object { const val MAX_SETTLE_SECONDS = 20L }
}

/**
 * Debounced wait over the change journal: returns once the watched chats have
 * received new incoming messages and then stayed quiet for the settle window.
 *
 * Stateless between calls: progress lives only in the cursor, so several clients
 * can wait on one account without consuming each other's messages. Quiet time is
 * measured from each message's journal timestamp, so a resumed cursor whose burst
 * is already old settles at once. A timeout during an unfinished burst returns no
 * messages and a cursor just before the burst, so the next call settles all of it.
 * Only incoming new messages extend a burst; edits, deletions and outgoing messages
 * are skipped and counted. The wait never sends or marks anything read.
 */
@Service
class SettledMessageWaiter(
    private val journal: ChangeJournal,
    private val client: TelegramClientService,
) {
    internal var clock: Clock = Clock.systemUTC()

    private class Pending(val entry: ChangeEntry, val facts: MessageAddressing) {
        val reasons = buildList {
            if (facts.privateChat) add("private_chat")
            if (facts.mentionsMe) add("mention")
            if (facts.repliesToMe) add("reply")
        }
    }

    fun await(account: String, request: SettleRequest, allowed: (Long) -> Boolean): SettledWaitResult =
        journal.withWaiterPermit(account) { run(account, request, allowed) }

    private fun run(account: String, request: SettleRequest, allowed: (Long) -> Boolean): SettledWaitResult {
        val started = System.nanoTime()
        fun remaining() = minOf(request.timeout.toNanos() - (System.nanoTime() - started),
            ToolCallDeadline.remainingNanos()?.minus(TimeUnit.MILLISECONDS.toNanos(50)) ?: Long.MAX_VALUE)
        fun writerBudget() = remaining().coerceIn(0L, TimeUnit.SECONDS.toNanos(2))
        val visible: (Long) -> Boolean = { (request.chatId == null || it == request.chatId) && allowed(it) }
        var cursor = request.cursor ?: journal.tailCursor(account, writerBudget())
        val burst = ArrayList<Pending>()
        var skipped = 0

        fun result(status: String, gapReasons: List<String> = emptyList(), hasMore: Boolean = false): SettledWaitResult {
            val returned = if (status == "timed_out") emptyList() else burst
            val last = burst.lastOrNull()?.let { Instant.parse(it.entry.observedAt) }
            val next = if (status == "timed_out" && burst.isNotEmpty()) rewind(cursor, burst.first().entry.sequence) else cursor
            return SettledWaitResult(
                status = status,
                settled = status == "settled",
                messages = returned.map {
                    SettledMessage(it.entry.chatId!!, it.entry.messageId!!, it.facts.senderId, it.entry.observedAt,
                        it.reasons.isNotEmpty(), it.reasons)
                },
                chats = returned.groupBy { it.entry.chatId!! }.map { (chatId, items) ->
                    SettledChat(chatId, items.size, items.minOf { it.entry.messageId!! }, items.maxOf { it.entry.messageId!! },
                        items.any { it.reasons.isNotEmpty() }, items.first().entry.observedAt, items.last().entry.observedAt)
                },
                nextCursor = next,
                hasMore = hasMore,
                gap = gapReasons.isNotEmpty(),
                reset = gapReasons.any { it in ChangeJournal.RESET_REASONS },
                gapReasons = gapReasons,
                timedOut = status == "timed_out",
                pendingMessages = if (status == "timed_out") burst.size else 0,
                settleSeconds = request.settle.seconds.toInt(),
                quietMillis = last?.let { Duration.between(it, clock.instant()).toMillis().coerceAtLeast(0) },
                skippedChanges = skipped,
            )
        }

        try {
            while (true) {
                val room = request.limit - burst.size
                if (room <= 0) return result("limit", hasMore = true)
                val scan = journal.scan(account, cursor, minOf(200, room), visible, writerBudget())
                val incoming = classify(scan.entries)
                for (entry in scan.entries) {
                    val facts = if (entry.kind == "new") incoming[entry.chatId to entry.messageId] else null
                    when {
                        entry.kind == ChangeJournal.GAP -> Unit
                        facts == null || facts.isOutgoing -> skipped++
                        else -> Pending(entry, facts).let { if (!request.addressedOnly || it.reasons.isNotEmpty()) burst += it else skipped++ }
                    }
                }
                cursor = scan.nextCursor
                if (scan.gap) return result(if (scan.reset) "reset" else "gap", scan.gapReasons, scan.hasMore)
                if (burst.size >= request.limit) return result("limit", hasMore = true)
                if (scan.hasMore && remaining() > 0) continue
                val untilSettled = burst.lastOrNull()?.let {
                    request.settle.toNanos() - Duration.between(Instant.parse(it.entry.observedAt), clock.instant()).toNanos()
                }
                if (untilSettled != null && untilSettled <= 0) return result("settled", hasMore = scan.hasMore)
                val left = remaining()
                if (left <= 0) return result("timed_out", hasMore = scan.hasMore)
                journal.pause(minOf(left, POLL_NANOS, untilSettled ?: Long.MAX_VALUE).coerceAtLeast(1_000_000L))
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw TelegramApiException(406, "Canceled settled wait: interrupted", interrupted, readOnlyRequest = true)
        }
    }

    /** Facts for the incoming-message candidates of one scan, fetched per chat without holding the journal lock. */
    private fun classify(entries: List<ChangeEntry>): Map<Pair<Long?, Long?>, MessageAddressing> =
        entries.filter { it.kind == "new" && it.chatId != null && it.messageId != null }
            .groupBy({ it.chatId!! }, { it.messageId!! })
            .flatMap { (chatId, ids) -> client.getMessageAddressing(chatId, ids.distinct()) }
            .associateBy { it.chatId to it.messageId }

    /** The cursor just before [sequence], in the same journal as [cursor]. */
    private fun rewind(cursor: String, sequence: Long) = "${cursor.substringBefore(':')}:${sequence - 1}"

    companion object {
        private val POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(50)
    }
}
