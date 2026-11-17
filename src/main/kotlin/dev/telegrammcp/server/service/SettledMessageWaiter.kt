package dev.telegrammcp.server.service

import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.client.ToolCallDeadline
import dev.telegrammcp.server.exception.EntityNotFoundException
import dev.telegrammcp.server.exception.TelegramApiException
import dev.telegrammcp.server.exception.TelegramErrorKind
import dev.telegrammcp.server.exception.TelegramTimeoutException
import dev.telegrammcp.server.util.StructuredLogger
import dev.telegrammcp.server.model.MessageAddressing
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/** One incoming message of a burst. Content is not included; hydrate it with get_messages. */
data class SettledMessage(val chatId: Long, val messageId: Long, val senderId: Long?, val observedAt: String,
    val addressed: Boolean, val addressedBy: List<String>)

/** Journal entries passed over, by reason; see [SettledMessageWaiter] for the reasons. */
data class SkippedChanges(val reason: String, val count: Int)

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
    val skippedReasons: List<SkippedChanges> = emptyList(),
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
    private val log = StructuredLogger.forClass<SettledMessageWaiter>()

    private class Pending(val entry: ChangeEntry, val facts: MessageAddressing) {
        val reasons = buildList {
            if (facts.privateChat) add("private_chat")
            if (facts.mentionsMe) add("mention")
            if (facts.repliesToMe) add("reply")
        }
    }

    fun await(account: String, request: SettleRequest, allowed: (Long) -> Boolean): SettledWaitResult =
        journal.withWaiterPermit(account) { run(account, request, allowed) }

    private class Lookup(val facts: Map<Pair<Long, Long>, MessageAddressing>, val failedChats: Set<Long>,
        val exhausted: Boolean)

    private fun run(account: String, request: SettleRequest, allowed: (Long) -> Boolean): SettledWaitResult {
        val started = System.nanoTime()
        fun remaining() = minOf(request.timeout.toNanos() - (System.nanoTime() - started),
            ToolCallDeadline.remainingNanos()?.minus(TimeUnit.MILLISECONDS.toNanos(50)) ?: Long.MAX_VALUE)
        fun writerBudget() = remaining().coerceIn(0L, TimeUnit.SECONDS.toNanos(2))
        val visible: (Long) -> Boolean = { (request.chatId == null || it == request.chatId) && allowed(it) }
        var cursor = request.cursor ?: journal.tailCursor(account, writerBudget())
        val burst = ArrayList<Pending>()
        val skipped = LinkedHashMap<String, Int>()
        var confirmed = false
        fun skip(reason: String, count: Int = 1) { if (count > 0) skipped.merge(reason, count, Int::plus) }

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
                skippedChanges = skipped.values.sum(),
                skippedReasons = skipped.map { (reason, count) -> SkippedChanges(reason, count) },
            )
        }

        try {
            while (true) {
                // Updates observed before this instant are in the scan (the journal flushes its writer first).
                val scanStarted = clock.instant()
                val scan = journal.scan(account, cursor, minOf(200, request.limit - burst.size), visible, writerBudget())
                if (scan.gap) {
                    // Catch-up covers everything here, so a known gap never waits on TDLib lookups.
                    skip(SKIP_BEHIND_GAP, scan.entries.count { it.kind != ChangeJournal.GAP })
                    cursor = scan.nextCursor
                    return result(if (scan.reset) "reset" else "gap", scan.gapReasons, scan.hasMore)
                }
                val lookup = lookUp(scan.entries, maxOf(remaining(), MIN_LOOKUP_NANOS))
                // Out of time before this page was classified: resume before it (and before any burst).
                if (lookup.exhausted) return result("timed_out", hasMore = true)
                val before = burst.size
                for (entry in scan.entries) {
                    val chatId = entry.chatId
                    val facts = if (entry.kind == "new") lookup.facts[chatId to entry.messageId] else null
                    when {
                        entry.kind == ChangeJournal.GAP -> Unit
                        entry.kind != "new" -> skip(SKIP_OTHER_CHANGE)
                        chatId != null && chatId in lookup.failedChats -> skip(SKIP_LOOKUP_FAILED)
                        facts == null -> skip(SKIP_VANISHED)
                        facts.isOutgoing -> skip(SKIP_OUTGOING)
                        else -> Pending(entry, facts).let {
                            if (!request.addressedOnly || it.reasons.isNotEmpty()) burst += it else skip(SKIP_NOT_ADDRESSED)
                        }
                    }
                }
                cursor = scan.nextCursor
                if (burst.size >= request.limit) return result("limit", hasMore = scan.hasMore)
                val left = remaining()
                if (scan.hasMore) {
                    if (left > 0) continue
                    return result("timed_out", hasMore = true)
                }
                val last = burst.lastOrNull()?.let { Instant.parse(it.entry.observedAt) }
                // Settled only from a complete look: the whole tail was scanned, it added nothing to the
                // burst, and the scan began after the quiet window had already elapsed.
                if (last != null && burst.size == before && Duration.between(last, scanStarted) >= request.settle) {
                    return result("settled")
                }
                val untilSettled = last?.let { request.settle.toNanos() - Duration.between(it, clock.instant()).toNanos() }
                if (untilSettled != null && untilSettled <= 0 && (left > 0 || !confirmed)) {
                    // Quiet now: confirm with one fresh scan, even at the deadline (an empty scan needs no lookups).
                    if (left <= 0) confirmed = true
                    continue
                }
                if (left <= 0) return result("timed_out")
                journal.pause(minOf(left, POLL_NANOS, untilSettled ?: Long.MAX_VALUE).coerceAtLeast(1_000_000L))
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw TelegramApiException(406, "Canceled settled wait: interrupted", interrupted, readOnlyRequest = true)
        }
    }

    /**
     * Facts for the incoming-message candidates of one scan, fetched per chat outside the journal lock
     * and within [budgetNanos]. A chat whose lookup fails for a chat-specific reason (removed from the
     * group, chat or messages gone) is reported in [Lookup.failedChats] so the cursor can move past it;
     * account-wide failures (unavailable client, flood wait, server errors) still fail the call.
     */
    private fun lookUp(entries: List<ChangeEntry>, budgetNanos: Long): Lookup {
        val byChat = entries.filter { it.kind == "new" && it.chatId != null && it.messageId != null }
            .groupBy({ it.chatId!! }, { it.messageId!! })
        if (byChat.isEmpty()) return Lookup(emptyMap(), emptySet(), false)
        val deadline = System.nanoTime() + budgetNanos
        val facts = HashMap<Pair<Long, Long>, MessageAddressing>()
        val failed = HashSet<Long>()
        for ((chatId, ids) in byChat) {
            val left = deadline - System.nanoTime()
            if (left <= 0) return Lookup(facts, failed, true)
            try {
                ToolCallDeadline.capped(Duration.ofNanos(left)) { client.getMessageAddressing(chatId, ids.distinct()) }
                    .forEach { facts[it.chatId to it.messageId] = it }
            } catch (timeout: TelegramTimeoutException) {
                if (timeout.reason == TelegramTimeoutException.Reason.LOCAL_CANCEL) throw timeout
                return Lookup(facts, failed, true)
            } catch (error: TelegramApiException) {
                if (error.kind !in CHAT_SPECIFIC_FAILURES) throw error
                log.warn("Skipping {} new message(s) in chat {}: lookup failed ({})", ids.size, chatId, error.kind)
                failed += chatId
            } catch (error: EntityNotFoundException) {
                log.warn("Skipping {} new message(s) in chat {}: lookup failed (not found)", ids.size, chatId)
                failed += chatId
            } catch (error: IllegalStateException) {
                log.warn("Skipping {} new message(s) in chat {}: lookup failed ({})", ids.size, chatId, error.javaClass.simpleName)
                failed += chatId
            }
        }
        return Lookup(facts, failed, false)
    }

    /** The cursor just before [sequence], in the same journal as [cursor]. */
    private fun rewind(cursor: String, sequence: Long) = "${cursor.substringBefore(':')}:${sequence - 1}"

    companion object {
        private val POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(50)
        /** Lets timeout_seconds=0 still classify what is already in the journal. */
        internal val MIN_LOOKUP_NANOS = TimeUnit.MILLISECONDS.toNanos(250)
        private val CHAT_SPECIFIC_FAILURES = setOf(TelegramErrorKind.NOT_FOUND, TelegramErrorKind.PERMISSION_DENIED,
            TelegramErrorKind.INVALID_INPUT)
        const val SKIP_OTHER_CHANGE = "not_a_new_message"
        const val SKIP_OUTGOING = "outgoing"
        const val SKIP_VANISHED = "message_gone"
        const val SKIP_NOT_ADDRESSED = "not_addressed"
        const val SKIP_LOOKUP_FAILED = "lookup_failed"
        const val SKIP_BEHIND_GAP = "behind_gap"
    }
}
