package dev.telegrammcp.server.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import dev.telegrammcp.server.client.McpCallCancellation
import dev.telegrammcp.server.client.McpCallCancelledException
import dev.telegrammcp.server.client.TelegramAccountRegistry
import dev.telegrammcp.server.client.ToolCallDeadline
import dev.telegrammcp.server.exception.TelegramApiException
import dev.telegrammcp.server.exception.TelegramBusyException
import dev.telegrammcp.server.exception.TelegramTimeoutException
import dev.telegrammcp.server.util.StructuredLogger
import it.tdlight.client.SimpleTelegramClientBuilder
import it.tdlight.jni.TdApi
import org.springframework.stereotype.Service
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class ChangeEntry(val sequence: Long, val kind: String, val chatId: Long?, val messageId: Long?, val observedAt: String)
data class ChangePage(val entries: List<ChangeEntry>, val nextCursor: String, val hasMore: Boolean,
    val gap: Boolean, val scope: String = "locally_observed_updates_only")
data class ChangeWaitResult(val entries: List<ChangeEntry>, val nextCursor: String, val hasMore: Boolean,
    val gap: Boolean, val timedOut: Boolean, val scope: String = "locally_observed_updates_only")
internal data class ChangeJournalHeader(val format: Int, val epoch: String)

/**
 * One scan of the journal with the reasons its coverage broke.
 *
 * [gapReasons] names every loss this scan crossed or that is still pending:
 * `restart` (a client session started), `session_accepted` (recording began
 * after the expected-account check), `reconnect`, `overflow`, `write_failed`,
 * `retention` (the cursor is older than the retained window), `journal_replaced`
 * (the cursor belongs to a deleted or recreated journal) and `unknown` (a marker
 * written by another process or an earlier run, whose cause this process cannot see).
 */
data class JournalScan(val entries: List<ChangeEntry>, val nextCursor: String, val hasMore: Boolean,
    val gapReasons: List<String>, val writerCaughtUp: Boolean) {
    val gap: Boolean get() = gapReasons.isNotEmpty()
    /** The session or journal started over, so anything in flight before it may be missing. */
    val reset: Boolean get() = gapReasons.any { it in ChangeJournal.RESET_REASONS }
}

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
    queueCapacity: Int = QUEUE_CAPACITY,
    private val maxWaitersPerAccount: Int = MAX_WAITERS_PER_ACCOUNT) : AutoCloseable {
    companion object {
        const val FORMAT = 1
        const val RETAINED = 10_000
        const val QUEUE_CAPACITY = 20_000
        const val MAX_WAITERS_PER_ACCOUNT = 25
        private const val MAX_BATCH = 4_096
        const val GAP = "coverage_gap"
        const val REASON_RESTART = "restart"
        const val REASON_SESSION_ACCEPTED = "session_accepted"
        const val REASON_RECONNECT = "reconnect"
        const val REASON_OVERFLOW = "overflow"
        const val REASON_WRITE_FAILED = "write_failed"
        const val REASON_RETENTION = "retention"
        const val REASON_JOURNAL_REPLACED = "journal_replaced"
        const val REASON_UNKNOWN = "unknown"
        val RESET_REASONS = setOf(REASON_RESTART, REASON_SESSION_ACCEPTED, REASON_JOURNAL_REPLACED)
        private const val MAX_MARKER_REASONS = 1_024
    }

    private class Observed(val account: String, val kind: String, val chatId: Long?, val messageId: Long?, val at: Instant,
        val reason: String? = null)
    /** Causes of the markers this process wrote, by sequence; kept in memory so the file format is unchanged. */
    private class MarkerReasons(val epoch: String, val bySequence: java.util.TreeMap<Long, String> = java.util.TreeMap())
    private data class LogVersion(val size: Long, val modified: FileTime, val created: FileTime, val fileKey: Any?)

    private class AccountLog(val file: Path, val epoch: String, val entries: ArrayDeque<ChangeEntry>,
        var lines: Int, var version: LogVersion) {
        val sequence get() = entries.lastOrNull()?.sequence ?: 0L
    }

    /** Observes accepted updates on the writer thread, outside the journal lock. Must not block. */
    fun interface Listener {
        fun onChange(account: String, kind: String, chatId: Long?, messageId: Long?)
    }

    private val log = StructuredLogger.forClass<ChangeJournal>()
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val listenerGaps: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val lineWriter = mapper.writer().without(SerializationFeature.INDENT_OUTPUT)
    private val queue = ArrayBlockingQueue<Observed>(queueCapacity)
    private val enqueued = AtomicLong()
    // Reserve before offering so a scan can see updates even while the writer drains the queue.
    private val pendingByAccount = ConcurrentHashMap<String, AtomicLong>()
    private val overflowed: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val writerStarted = AtomicBoolean()
    @Volatile private var writer: Thread? = null
    @Volatile private var closed = false

    // Guarded by lock; the writer holds it only while committing a batch.
    private val lock = ReentrantLock()
    private val logs = HashMap<String, AccountLog>()
    private val waiters = ConcurrentHashMap<String, Semaphore>()
    private val failed = HashSet<String>()
    private val markerReasons = HashMap<String, MarkerReasons>()
    private var handled = 0L
    private var prepared = false

    /** Never blocks or touches the disk: safe on TDLib's update thread. */
    fun append(account: String, kind: String, chatId: Long? = null, messageId: Long? = null) =
        enqueue(account, kind, chatId, messageId, null)

    /** Appends a [GAP] marker whose cause [scan] reports while this process runs. */
    internal fun appendGap(account: String, reason: String) = enqueue(account, GAP, null, null, reason)

    private fun enqueue(account: String, kind: String, chatId: Long?, messageId: Long?, reason: String?) {
        if (closed) return
        val label = TelegramAccountRegistry.normalizeLabel(account)
        val update = Observed(label, kind, chatId, messageId, Instant.now(), reason)
        val pending = pendingByAccount.computeIfAbsent(label) { AtomicLong() }
        pending.incrementAndGet()
        if (queue.offer(update)) enqueued.incrementAndGet()
        else {
            overflowed += label
            if (listeners.isNotEmpty()) listenerGaps += label
            pending.decrementAndGet()
        }
        if (writerStarted.compareAndSet(false, true)) {
            writer = Thread(::drain, "telegram-change-journal").apply { isDaemon = true; start() }
        }
    }

    fun addListener(listener: Listener) {
        listeners += listener
    }

    fun page(account: String, cursor: String?, limit: Int, allowed: (Long) -> Boolean): ChangePage {
        require(limit in 1..200)
        val label = TelegramAccountRegistry.normalizeLabel(account)
        awaitWriter()
        if (!acquireReadLock(TimeUnit.SECONDS.toNanos(2))) throw journalBusy(label)
        return try { readPage(label, cursor, limit, allowed) } finally { lock.unlock() }
    }

    /** A cursor at the durable journal tail. */
    fun tailCursor(account: String, budgetNanos: Long = TimeUnit.SECONDS.toNanos(2)): String =
        readWithinBudget(account, budgetNanos) { _, journal -> "${journal.epoch}:${journal.sequence}" }
            ?: throw journalBusy(TelegramAccountRegistry.normalizeLabel(account))

    /**
     * Like [page], but reports why coverage broke, and treats a cursor from a
     * replaced journal as a reset at the current tail instead of an error.
     * The reader returns null if the journal locks are busy at the deadline.
     */
    fun scan(account: String, cursor: String, limit: Int, allowed: (Long) -> Boolean,
        budgetNanos: Long = TimeUnit.SECONDS.toNanos(2)): JournalScan? {
        require(limit in 1..200)
        return readWithinBudget(account, budgetNanos) { label, journal ->
            readScan(label, journal, cursor, limit, allowed, replacedIsReset = true)
        }
    }

    private fun <T : Any> readWithinBudget(account: String, budgetNanos: Long,
        read: (String, AccountLog) -> T): T? {
        val label = TelegramAccountRegistry.normalizeLabel(account)
        val started = System.nanoTime()
        fun remaining() = (budgetNanos - (System.nanoTime() - started)).coerceAtLeast(0)
        if (!isWriterCaughtUp(label)) awaitWriter(remaining())
        if (!acquireReadLock(remaining())) return null
        return try {
            if (closed) throw TelegramApiException(406, "Canceled change wait: journal closed", readOnlyRequest = true)
            withLogDirectory { directory ->
                DurableFiles.tryLockedPrepared(directory, ::remaining) {
                    read(label, loadLog(directory, label))
                }
            }
        } finally { lock.unlock() }
    }

    /** Runs [action] under the per-account waiter limit shared with [waitForChanges]. */
    fun <T> withWaiterPermit(account: String, action: () -> T): T {
        val label = TelegramAccountRegistry.normalizeLabel(account)
        val permits = waiters.computeIfAbsent(label) { Semaphore(maxWaitersPerAccount) }
        if (!permits.tryAcquire()) throw TelegramBusyException(label, maxWaitersPerAccount, retryable = true)
        try { return action() } finally { permits.release() }
    }

    /** A bounded pause between scans that ends early on MCP cancellation. */
    fun pause(nanos: Long) {
        if (nanos > 0) pauseReader(nanos)
    }

    /**
     * Waits for visible changes without polling Telegram or changing read receipts.
     * An omitted cursor starts at the current tail; a supplied cursor resumes exactly
     * like [page]. Filtered entries advance the cursor without resetting the deadline.
     * Each page scan holds the journal lock. Between scans the caller polls off
     * that lock, so a writer stalled on disk cannot trap it in condition re-acquisition.
     */
    fun waitForChanges(account: String, cursor: String?, limit: Int, timeout: Duration,
        allowed: (Long) -> Boolean): ChangeWaitResult {
        require(limit in 1..200)
        require(!timeout.isNegative && timeout <= Duration.ofSeconds(30)) { "timeout must be in 0..30 seconds" }
        val label = TelegramAccountRegistry.normalizeLabel(account)
        val permits = waiters.computeIfAbsent(label) { Semaphore(maxWaitersPerAccount) }
        if (!permits.tryAcquire()) throw TelegramBusyException(label, maxWaitersPerAccount, retryable = true)
        val started = System.nanoTime()
        val budget = timeout.toNanos()
        fun remaining() = minOf(budget - (System.nanoTime() - started),
            ToolCallDeadline.remainingNanos()?.minus(TimeUnit.MILLISECONDS.toNanos(50)) ?: Long.MAX_VALUE)
        try {
            awaitWriter(remaining().coerceIn(0L, TimeUnit.SECONDS.toNanos(2)))
            var next = cursor
            while (true) {
                if (!acquireReadLock(remaining().coerceAtLeast(0))) throw journalBusy(label)
                val left: Long
                try {
                    if (next == null) next = withLog(label) { "${it.epoch}:${it.sequence}" }
                    if (closed) throw TelegramApiException(406, "Canceled change wait: journal closed", readOnlyRequest = true)
                    val page = readPage(label, next, limit, allowed)
                    next = page.nextCursor
                    if (page.entries.isNotEmpty() || page.gap) return page.waitResult(false)
                    left = remaining()
                    if (left <= 0) return page.waitResult(true)
                    if (page.hasMore) continue
                } finally { lock.unlock() }
                // A different process can append too. Poll its durable state
                // without holding or waiting to re-acquire the writer's lock.
                pauseReader(minOf(left, 50_000_000L))
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw TelegramApiException(406, "Canceled change wait: interrupted", interrupted, readOnlyRequest = true)
        } finally { permits.release() }
    }

    private fun ChangePage.waitResult(timedOut: Boolean) =
        ChangeWaitResult(entries, nextCursor, hasMore, gap, timedOut, scope)

    /** Caller holds [lock]; file and cursor rules are shared by immediate and waiting reads. */
    private fun readPage(label: String, cursor: String?, limit: Int, allowed: (Long) -> Boolean): ChangePage =
        withLog(label) { journal ->
            val scan = readScan(label, journal, cursor, limit, allowed, replacedIsReset = false)
            ChangePage(scan.entries, scan.nextCursor, scan.hasMore, scan.gap)
        }

    /** Caller holds [lock]. */
    private fun readScan(label: String, journal: AccountLog, cursor: String?, limit: Int, allowed: (Long) -> Boolean,
        replacedIsReset: Boolean): JournalScan {
        val parts = cursor?.split(":")
        if (replacedIsReset && parts != null && parts.size == 2 && parts[0] != journal.epoch) {
            val validEpoch = runCatching { UUID.fromString(parts[0]).toString().equals(parts[0], ignoreCase = true) }
                .getOrDefault(false)
            require(validEpoch && parts[1].toLongOrNull()?.let { it >= 0 } == true) { "Invalid change cursor" }
            return JournalScan(emptyList(), "${journal.epoch}:${journal.sequence}", false,
                listOf(REASON_JOURNAL_REPLACED), isWriterCaughtUp(label))
        }
        require(parts == null || (parts.size == 2 && parts[0] == journal.epoch)) { "Cursor belongs to a different journal; restart without cursor" }
        val after = if (parts == null) 0L else requireNotNull(parts[1].toLongOrNull()) { "Invalid change cursor" }
        require(after in 0..journal.sequence) { "Invalid change cursor" }
        val scanned = journal.entries.asSequence().filter { it.sequence > after }.take(limit).toList()
        val known = markerReasons[label]?.takeIf { it.epoch == journal.epoch }?.bySequence
        val reasons = LinkedHashSet<String>()
        if (after < (journal.entries.firstOrNull()?.sequence ?: 1) - 1) reasons += REASON_RETENTION
        scanned.filter { it.kind == GAP }.forEach { reasons += known?.get(it.sequence) ?: REASON_UNKNOWN }
        val visible = scanned.filter { it.chatId == null || allowed(it.chatId) }
        val writerCaughtUp = isWriterCaughtUp(label)
        // A loss that is not yet written as a marker; the marker follows and is reported again.
        if (label in failed) reasons += REASON_WRITE_FAILED
        if (label in overflowed) reasons += REASON_OVERFLOW
        return JournalScan(visible,
            "${journal.epoch}:${scanned.lastOrNull()?.sequence ?: journal.sequence}",
            scanned.lastOrNull()?.sequence?.let { it < journal.sequence } ?: false,
            reasons.toList(), writerCaughtUp)
    }

    private fun isWriterCaughtUp(label: String): Boolean = (pendingByAccount[label]?.get() ?: 0L) == 0L

    /**
     * Records [builder]'s message updates for [account].
     *
     * With [recording] false (an expected-account check is still pending) the
     * handlers drop every update until [Attachment.activate] is called, so a
     * refused session never reaches the journal. Activation appends a gap
     * marker, so cursors from before cannot mix the two sessions.
     */
    fun attach(builder: SimpleTelegramClientBuilder, account: String, recording: Boolean = true): Attachment {
        val attachment = Attachment(account, recording)
        if (recording) appendGap(account, REASON_RESTART)
        builder.addUpdateHandler(TdApi.UpdateNewMessage::class.java) { attachment.record { recordNewMessage(account, it.message) } }
        builder.addUpdateHandler(TdApi.UpdateMessageSendSucceeded::class.java) { attachment.record { recordNewMessage(account, it.message) } }
        builder.addUpdateHandler(TdApi.UpdateMessageContent::class.java) {
            attachment.record { append(account, "content_changed", it.chatId, it.messageId) }
        }
        builder.addUpdateHandler(TdApi.UpdateMessageEdited::class.java) { attachment.record { append(account, "edited", it.chatId, it.messageId) } }
        builder.addUpdateHandler(TdApi.UpdateDeleteMessages::class.java) { update ->
            attachment.record { if (update.isPermanent) update.messageIds.forEach { append(account, "deleted", update.chatId, it) } }
        }
        builder.addUpdateHandler(TdApi.UpdateConnectionState::class.java) {
            attachment.record { if (it.state !is TdApi.ConnectionStateReady) appendGap(account, REASON_RECONNECT) }
        }
        return attachment
    }

    /** One client's journal hook; see [attach]. */
    inner class Attachment internal constructor(private val account: String, recording: Boolean) {
        private val active = AtomicBoolean(recording)

        val recording: Boolean get() = active.get()

        /** Starts recording once the session is accepted; the first call appends a gap marker. */
        fun activate() {
            if (active.compareAndSet(false, true)) appendGap(account, REASON_SESSION_ACCEPTED)
        }

        internal fun record(action: () -> Unit) {
            if (active.get()) action()
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
    private fun awaitWriter(timeoutNanos: Long = TimeUnit.SECONDS.toNanos(2)) {
        val target = enqueued.get()
        val started = System.nanoTime()
        val budget = timeoutNanos.coerceAtLeast(0)
        while (true) {
            val remaining = (budget - (System.nanoTime() - started)).coerceAtLeast(0)
            if (!acquireReadLock(remaining)) return
            try { if (handled >= target) return } finally { lock.unlock() }
            val pause = budget - (System.nanoTime() - started)
            if (pause <= 0) return
            pauseReader(minOf(pause, 50_000_000L))
        }
    }

    private fun pauseReader(nanos: Long) {
        try {
            McpCallCancellation.await(CompletableFuture<Unit>(), nanos)
        } catch (_: TimeoutException) {
            // Recheck the journal after this bounded local pause.
        } catch (cancelled: McpCallCancelledException) {
            McpCallCancellation.throwIfLocalWait("change-journal")
            throw cancelled
        }
    }

    /** Only the local reader stops; the writer and any already-started disk I/O stay intact. */
    private fun acquireReadLock(budgetNanos: Long): Boolean {
        val started = System.nanoTime()
        while (true) {
            McpCallCancellation.throwIfLocalWait("change-journal")
            val toolRemaining = ToolCallDeadline.remainingNanos() ?: Long.MAX_VALUE
            if (toolRemaining <= 0) {
                throw TelegramTimeoutException(
                    "change-journal", TelegramTimeoutException.Reason.TOOL_DEADLINE,
                    requestSent = false, retryable = !ToolCallDeadline.writeDispatched(),
                    waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                ).apply { healthFailure = false }
            }
            // An immediate read remains valid even for a zero long-poll budget.
            if (lock.tryLock()) return true
            val remaining = budgetNanos - (System.nanoTime() - started)
            if (remaining <= 0) return false
            if (lock.tryLock(minOf(remaining, toolRemaining, 50_000_000L), TimeUnit.NANOSECONDS)) return true
        }
    }

    private fun journalBusy(account: String) =
        TelegramBusyException("change-journal:$account", 1, retryable = !ToolCallDeadline.writeDispatched())

    private fun drain() {
        val batch = ArrayList<Observed>(MAX_BATCH)
        while (true) {
            val first = queue.poll(250, TimeUnit.MILLISECONDS)
            if (first == null) { if (closed) return else continue }
            batch += first
            queue.drainTo(batch, MAX_BATCH - 1)
            lock.withLock {
                try {
                    batch.groupBy { it.account }.forEach { (account, items) ->
                        try { persist(account, items) }
                        finally { pendingByAccount.getValue(account).addAndGet(-items.size.toLong()) }
                    }
                } finally { handled += batch.size }
            }
            dispatch(batch)
            batch.clear()
        }
    }

    private fun dispatch(batch: List<Observed>) {
        if (listeners.isEmpty()) return
        fun deliver(account: String, kind: String, chatId: Long?, messageId: Long?) = listeners.forEach { listener ->
            try { listener.onChange(account, kind, chatId, messageId) }
            catch (failure: Exception) { log.warn("Change listener failed: {}", failure.javaClass.simpleName) }
        }
        batch.forEach { deliver(it.account, it.kind, it.chatId, it.messageId) }
        // Overflow dropped updates that listeners never saw; they learn of the loss once, as a gap.
        listenerGaps.toList().forEach { if (listenerGaps.remove(it)) deliver(it, GAP, null, null) }
    }

    private fun persist(account: String, items: List<Observed>) {
        try {
            withLog(account) { journal ->
                var next = journal.sequence
                val entries = ArrayList<ChangeEntry>(items.size + 2)
                val reasons = HashMap<Long, String>()
                fun add(kind: String, chatId: Long?, messageId: Long?, at: Instant, reason: String? = null) {
                    entries += ChangeEntry(++next, kind, chatId, messageId, at.toString())
                    if (kind == GAP && reason != null) reasons[next] = reason
                }
                // A failed batch was lost before these updates; overflowed ones arrived after them.
                if (account in failed) add(GAP, null, null, items.first().at, REASON_WRITE_FAILED)
                items.forEach { add(it.kind, it.chatId, it.messageId, it.at, it.reason) }
                if (overflowed.remove(account)) add(GAP, null, null, Instant.now(), REASON_OVERFLOW)
                write(journal, entries)
                rememberReasons(account, journal, reasons)
            }
            failed -= account
        } catch (failure: Exception) {
            failed += account
            logs.remove(account)
            log.warn("Change journal write failed for account {}; a coverage gap follows: {}", account, failure.message)
        }
    }

    /** Caller holds [lock]. Bounded to the retained window; a replaced journal starts a new map. */
    private fun rememberReasons(account: String, journal: AccountLog, reasons: Map<Long, String>) {
        if (reasons.isEmpty()) return
        val known = markerReasons[account]?.takeIf { it.epoch == journal.epoch }
            ?: MarkerReasons(journal.epoch).also { markerReasons[account] = it }
        known.bySequence.putAll(reasons)
        val oldest = journal.entries.firstOrNull()?.sequence ?: 0L
        known.bySequence.headMap(oldest).clear()
        while (known.bySequence.size > MAX_MARKER_REASONS) known.bySequence.pollFirstEntry()
    }

    /** Caller holds [lock]. The store lock also serializes other processes sharing this data directory. */
    private fun <T> withLog(account: String, action: (AccountLog) -> T): T = withLogDirectory { directory ->
        DurableFiles.lockedPrepared(directory) { action(loadLog(directory, account)) }
    }

    private fun <T> withLogDirectory(action: (Path) -> T): T {
        val directory = paths.applicationDataDirectory.resolve("changes")
        if (!prepared) { DurableFiles.privateDirectory(directory); prepared = true }
        return try { action(directory) } catch (failure: IOException) {
            prepared = false
            throw failure
        }
    }

    /** Caller holds the journal and file locks. */
    private fun loadLog(directory: Path, account: String): AccountLog {
        val file = directory.resolve("$account.jsonl")
        val cached = logs[account]
        val version = try { logVersion(file) } catch (_: NoSuchFileException) { null }
        if (cached != null && cached.version == version) return cached
        logs.remove(account)
        return load(file, version).also { logs[account] = it }
    }

    private fun logVersion(file: Path): LogVersion {
        val attributes = Files.readAttributes(file, BasicFileAttributes::class.java)
        return LogVersion(attributes.size(), attributes.lastModifiedTime(), attributes.creationTime(), attributes.fileKey())
    }

    private fun write(journal: AccountLog, entries: List<ChangeEntry>) {
        val bytes = entries.joinToString("") { lineWriter.writeValueAsString(it) + "\n" }.toByteArray(Charsets.UTF_8)
        DurableFiles.append(journal.file, bytes)
        journal.version = logVersion(journal.file)
        journal.lines += entries.size
        journal.entries.addAll(entries)
        while (journal.entries.size > RETAINED) journal.entries.removeFirst()
        if (journal.lines - 1 > 2 * RETAINED) compact(journal)
    }

    private fun compact(journal: AccountLog) {
        val bytes = render(journal.epoch, journal.entries)
        DurableFiles.write(journal.file, bytes)
        journal.version = logVersion(journal.file)
        journal.lines = journal.entries.size + 1
    }

    private fun render(epoch: String, entries: Collection<ChangeEntry>): ByteArray =
        (sequenceOf<Any>(ChangeJournalHeader(FORMAT, epoch)) + entries.asSequence())
            .joinToString("") { lineWriter.writeValueAsString(it) + "\n" }.toByteArray(Charsets.UTF_8)

    private fun load(file: Path, version: LogVersion?): AccountLog {
        if (version == null) {
            val epoch = UUID.randomUUID().toString()
            val bytes = render(epoch, emptyList())
            DurableFiles.write(file, bytes)
            return AccountLog(file, epoch, ArrayDeque(), 1, logVersion(file))
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
        return AccountLog(file, header.epoch, entries, records.size, version)
            .also { if (!complete) compact(it) }
    }
}
