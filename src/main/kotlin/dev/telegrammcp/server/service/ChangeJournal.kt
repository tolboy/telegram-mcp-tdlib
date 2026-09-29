package dev.telegrammcp.server.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import dev.telegrammcp.server.client.TelegramAccountRegistry
import dev.telegrammcp.server.util.StructuredLogger
import it.tdlight.client.SimpleTelegramClientBuilder
import it.tdlight.jni.TdApi
import org.springframework.stereotype.Service
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class ChangeEntry(val sequence: Long, val kind: String, val chatId: Long?, val messageId: Long?, val observedAt: String)
data class ChangePage(val entries: List<ChangeEntry>, val nextCursor: String, val hasMore: Boolean,
    val gap: Boolean, val scope: String = "locally_observed_updates_only")
internal data class ChangeJournalHeader(val format: Int, val epoch: String)

/**
 * Bounded metadata journal of locally observed TDLib updates.
 *
 * TDLib calls update handlers on the thread that also delivers every request
 * result, so a handler only enqueues. One writer thread appends batches to a
 * per-account JSON-lines log. Restart, reconnect, queue-overflow and failed-write
 * markers explicitly invalidate continuous coverage. A marker is always appended
 * after the loss it reports, so every reader that reads past the loss sees it.
 */
@Service
class ChangeJournal(private val paths: PlatformPaths, private val mapper: ObjectMapper,
    queueCapacity: Int = QUEUE_CAPACITY) : AutoCloseable {
    companion object {
        const val FORMAT = 1
        const val RETAINED = 10_000
        const val QUEUE_CAPACITY = 20_000
        private const val MAX_BATCH = 4_096
        private const val GAP = "coverage_gap"
    }

    private class Observed(val account: String, val kind: String, val chatId: Long?, val messageId: Long?, val at: Instant)
    private class AccountLog(val file: Path, val epoch: String, val entries: ArrayDeque<ChangeEntry>, var lines: Int, var size: Long) {
        val sequence get() = entries.lastOrNull()?.sequence ?: 0L
    }

    private val log = StructuredLogger.forClass<ChangeJournal>()
    private val lineWriter = mapper.writer().without(SerializationFeature.INDENT_OUTPUT)
    private val queue = ArrayBlockingQueue<Observed>(queueCapacity)
    private val enqueued = AtomicLong()
    private val overflowed: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val writerStarted = AtomicBoolean()
    @Volatile private var writer: Thread? = null
    @Volatile private var closed = false

    // Guarded by lock; the writer holds it only while committing a batch.
    private val lock = ReentrantLock()
    private val progress = lock.newCondition()
    private val logs = HashMap<String, AccountLog>()
    private val failed = HashSet<String>()
    private var handled = 0L
    private var prepared = false

    /** Never blocks or touches the disk: safe on TDLib's update thread. */
    fun append(account: String, kind: String, chatId: Long? = null, messageId: Long? = null) {
        if (closed) return
        val label = TelegramAccountRegistry.normalizeLabel(account)
        if (queue.offer(Observed(label, kind, chatId, messageId, Instant.now()))) enqueued.incrementAndGet()
        else overflowed += label
        if (writerStarted.compareAndSet(false, true)) {
            writer = Thread(::drain, "telegram-change-journal").apply { isDaemon = true; start() }
        }
    }

    fun page(account: String, cursor: String?, limit: Int, allowed: (Long) -> Boolean): ChangePage {
        require(limit in 1..200)
        val label = TelegramAccountRegistry.normalizeLabel(account)
        awaitWriter()
        return lock.withLock {
            // Even an empty journal is created here: its returned cursor needs a persistent epoch.
            withLog(label) { journal ->
                val parts = cursor?.split(":")
                require(parts == null || (parts.size == 2 && parts[0] == journal.epoch)) { "Cursor belongs to a different journal; restart without cursor" }
                val after = if (parts == null) 0L else requireNotNull(parts[1].toLongOrNull()) { "Invalid change cursor" }
                require(after in 0..journal.sequence) { "Invalid change cursor" }
                val scanned = journal.entries.asSequence().filter { it.sequence > after }.take(limit).toList()
                ChangePage(scanned.filter { it.chatId == null || allowed(it.chatId) },
                    "${journal.epoch}:${scanned.lastOrNull()?.sequence ?: journal.sequence}",
                    scanned.lastOrNull()?.sequence?.let { it < journal.sequence } ?: false,
                    label in failed || label in overflowed || after < (journal.entries.firstOrNull()?.sequence ?: 1) - 1 ||
                        scanned.any { it.kind == GAP })
            }
        }
    }

    fun attach(builder: SimpleTelegramClientBuilder, account: String) {
        append(account, GAP)
        builder.addUpdateHandler(TdApi.UpdateNewMessage::class.java) { recordNewMessage(account, it.message) }
        builder.addUpdateHandler(TdApi.UpdateMessageSendSucceeded::class.java) { recordNewMessage(account, it.message) }
        builder.addUpdateHandler(TdApi.UpdateMessageContent::class.java) { append(account, "content_changed", it.chatId, it.messageId) }
        builder.addUpdateHandler(TdApi.UpdateMessageEdited::class.java) { append(account, "edited", it.chatId, it.messageId) }
        builder.addUpdateHandler(TdApi.UpdateDeleteMessages::class.java) { update ->
            if (update.isPermanent) update.messageIds.forEach { append(account, "deleted", update.chatId, it) }
        }
        builder.addUpdateHandler(TdApi.UpdateConnectionState::class.java) {
            if (it.state !is TdApi.ConnectionStateReady) append(account, GAP)
        }
    }

    internal fun recordNewMessage(account: String, message: TdApi.Message) {
        // Outgoing UpdateNewMessage uses a provisional ID that disappears when
        // sending completes. Publish the final ID from UpdateMessageSendSucceeded.
        if (message.sendingState == null) append(account, "new", message.chatId, message.id)
    }

    /** Flushes queued updates; later ones are dropped and covered by the next start's restart marker. */
    override fun close() {
        closed = true
        writer?.join(TimeUnit.SECONDS.toMillis(5))
    }

    /** Makes updates observed before this call readable, unless the writer is stalled. */
    private fun awaitWriter() {
        val target = enqueued.get()
        var remaining = TimeUnit.SECONDS.toNanos(2)
        lock.withLock { while (handled < target && remaining > 0) remaining = progress.awaitNanos(remaining) }
    }

    private fun drain() {
        val batch = ArrayList<Observed>(MAX_BATCH)
        while (true) {
            val first = queue.poll(250, TimeUnit.MILLISECONDS)
            if (first == null) { if (closed) return else continue }
            batch += first
            queue.drainTo(batch, MAX_BATCH - 1)
            lock.withLock {
                try { batch.groupBy { it.account }.forEach { (account, items) -> persist(account, items) } }
                finally { handled += batch.size; progress.signalAll() }
            }
            batch.clear()
        }
    }

    private fun persist(account: String, items: List<Observed>) {
        try {
            withLog(account) { journal ->
                var next = journal.sequence
                val entries = ArrayList<ChangeEntry>(items.size + 2)
                fun add(kind: String, chatId: Long?, messageId: Long?, at: Instant) {
                    entries += ChangeEntry(++next, kind, chatId, messageId, at.toString())
                }
                // A failed batch was lost before these updates; overflowed ones arrived after them.
                if (account in failed) add(GAP, null, null, items.first().at)
                items.forEach { add(it.kind, it.chatId, it.messageId, it.at) }
                if (overflowed.remove(account)) add(GAP, null, null, Instant.now())
                write(journal, entries)
            }
            failed -= account
        } catch (failure: Exception) {
            failed += account
            logs.remove(account)
            log.warn("Change journal write failed for account {}; a coverage gap follows: {}", account, failure.message)
        }
    }

    /** Caller holds [lock]. The store lock also serializes other processes sharing this data directory. */
    private fun <T> withLog(account: String, action: (AccountLog) -> T): T {
        val directory = paths.applicationDataDirectory.resolve("changes")
        if (!prepared) { DurableFiles.privateDirectory(directory); prepared = true }
        return try {
            DurableFiles.lockedPrepared(directory) {
                val file = directory.resolve("$account.jsonl")
                val cached = logs[account]
                // Appends only grow the log and compaction rewrites it, so a size change reveals another writer.
                val journal = if (cached != null && Files.exists(file) && Files.size(file) == cached.size) cached
                    else { logs.remove(account); load(file).also { logs[account] = it } }
                action(journal)
            }
        } catch (failure: IOException) {
            prepared = false
            throw failure
        }
    }

    private fun write(journal: AccountLog, entries: List<ChangeEntry>) {
        val bytes = entries.joinToString("") { lineWriter.writeValueAsString(it) + "\n" }.toByteArray(Charsets.UTF_8)
        DurableFiles.append(journal.file, bytes)
        journal.size += bytes.size
        journal.lines += entries.size
        journal.entries.addAll(entries)
        while (journal.entries.size > RETAINED) journal.entries.removeFirst()
        if (journal.lines - 1 > 2 * RETAINED) compact(journal)
    }

    private fun compact(journal: AccountLog) {
        val bytes = render(journal.epoch, journal.entries)
        DurableFiles.write(journal.file, bytes)
        journal.size = bytes.size.toLong()
        journal.lines = journal.entries.size + 1
    }

    private fun render(epoch: String, entries: Collection<ChangeEntry>): ByteArray =
        (sequenceOf<Any>(ChangeJournalHeader(FORMAT, epoch)) + entries.asSequence())
            .joinToString("") { lineWriter.writeValueAsString(it) + "\n" }.toByteArray(Charsets.UTF_8)

    private fun load(file: Path): AccountLog {
        if (!Files.exists(file)) {
            val epoch = UUID.randomUUID().toString()
            val bytes = render(epoch, emptyList())
            DurableFiles.write(file, bytes)
            return AccountLog(file, epoch, ArrayDeque(), 1, bytes.size.toLong())
        }
        val bytes = Files.readAllBytes(file)
        val text = String(bytes, Charsets.UTF_8)
        val complete = text.endsWith('\n')
        val records = text.split('\n').let { if (complete) it.dropLast(1) else it }
        val header = runCatching { mapper.readValue(records.first(), ChangeJournalHeader::class.java) }.getOrNull()
        check(header != null && header.format == FORMAT && header.epoch.isNotBlank()) { "Change journal $file has an unsupported header" }
        val entries = ArrayDeque<ChangeEntry>()
        records.drop(1).forEachIndexed { index, line ->
            val entry = runCatching { mapper.readValue(line, ChangeEntry::class.java) }.getOrNull()
            if (entry == null) {
                // Only an interrupted final append may be torn; malformed history fails closed.
                check(!complete && index == records.size - 2) { "Change journal $file is malformed at line ${index + 2}" }
                return@forEachIndexed
            }
            check(entries.isEmpty() || entry.sequence > entries.last().sequence) { "Change journal $file is out of order at line ${index + 2}" }
            entries += entry
        }
        while (entries.size > RETAINED) entries.removeFirst()
        return AccountLog(file, header.epoch, entries, records.size, bytes.size.toLong())
            .also { if (!complete) compact(it) }
    }
}
