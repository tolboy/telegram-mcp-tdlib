package dev.telegrammcp.server.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.client.McpCallCancellation
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.client.ToolCallDeadline
import dev.telegrammcp.server.exception.TelegramApiException
import dev.telegrammcp.server.exception.TelegramBusyException
import dev.telegrammcp.server.exception.TelegramTimeoutException
import dev.telegrammcp.server.exception.TelegramUnavailableException
import dev.telegrammcp.server.model.MessageAddressing
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.*

class SettledMessageWaiterTest {
    @TempDir lateinit var root: Path
    private val journals = mutableListOf<ChangeJournal>()
    private val threads = mutableListOf<Thread>()
    private val facts = ConcurrentHashMap<Pair<Long, Long>, MessageAddressing>()
    private val client = mockk<TelegramClientService>()

    @AfterEach fun close() {
        threads.forEach(Thread::interrupt)
        threads.forEach { it.join(2_000) }
        journals.forEach(ChangeJournal::close)
    }

    private fun journal(waiters: Int = ChangeJournal.MAX_WAITERS_PER_ACCOUNT): ChangeJournal {
        val paths = mockk<PlatformPaths>()
        every { paths.applicationDataDirectory } returns root
        every { client.getMessageAddressing(any(), any()) } answers {
            val chatId = firstArg<Long>()
            secondArg<List<Long>>().mapNotNull { facts[chatId to it] }
        }
        return ChangeJournal(paths, jacksonObjectMapper(), maxWaitersPerAccount = waiters).also { journals += it }
    }

    private fun ChangeJournal.message(chatId: Long, messageId: Long, outgoing: Boolean = false, private: Boolean = false,
        mention: Boolean = false, reply: Boolean = false, record: Boolean = true) {
        facts[chatId to messageId] = MessageAddressing(chatId, messageId, 900L + messageId, outgoing, private, mention, reply)
        if (record) append("default", "new", chatId, messageId)
    }

    private fun request(cursor: String?, settleMillis: Long = 300, timeoutSeconds: Long = 5, chatId: Long? = null,
        limit: Int = 50, addressedOnly: Boolean = false) =
        SettleRequest(cursor, chatId, Duration.ofMillis(settleMillis), Duration.ofSeconds(timeoutSeconds), limit, addressedOnly)

    private fun <T> worker(action: () -> T): Pair<Thread, CompletableFuture<T>> {
        val result = CompletableFuture<T>()
        val worker = thread(isDaemon = true) {
            try { result.complete(action()) } catch (error: Throwable) { result.completeExceptionally(error) }
        }
        threads += worker
        return worker to result
    }

    private fun awaitWaiting(worker: Thread) = assertTimeoutPreemptively(Duration.ofSeconds(3)) {
        while (worker.state != Thread.State.TIMED_WAITING) {
            check(worker.isAlive) { "Waiter exited before waiting" }
            Thread.yield()
        }
    }

    @Test fun `a burst is returned once the chat has been quiet for the settle window`() {
        val journal = journal()
        val waiter = SettledMessageWaiter(journal, client)
        val cursor = journal.tailCursor("default")
        val wait = worker { waiter.await("default", request(cursor, settleMillis = 600)) { true } }
        awaitWaiting(wait.first)
        val started = System.nanoTime()
        journal.message(42, 1, private = true)
        Thread.sleep(300)
        assertFalse(wait.second.isDone, "returned in the middle of a burst")
        journal.message(42, 2, private = true)
        val result = wait.second.get(5, TimeUnit.SECONDS)
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertEquals("settled", result.status)
        assertTrue(result.settled)
        assertEquals(listOf(1L, 2L), result.messages.map { it.messageId })
        // The second message restarted the quiet window: 300 ms + 600 ms.
        assertTrue(elapsed >= 850, "settled after only $elapsed ms")
        assertTrue(result.quietMillis!! >= 550, "quiet for ${result.quietMillis} ms")
        assertEquals(SettledChat(42, 2, 1, 2, true, result.messages[0].observedAt, result.messages[1].observedAt),
            result.chats.single())
        assertEquals(journal.tailCursor("default"), result.nextCursor)
        assertFalse(result.gap || result.reset || result.timedOut)
        assertEquals(listOf("private_chat"), result.messages[0].addressedBy)
        assertEquals(901L, result.messages[0].senderId)
    }

    @Test fun `outgoing messages, edits, deletions and vanished messages are skipped and do not extend a burst`() {
        val journal = journal()
        val waiter = SettledMessageWaiter(journal, client)
        val cursor = journal.tailCursor("default")
        journal.message(42, 1)
        journal.message(42, 2, outgoing = true)
        journal.append("default", "edited", 42, 1)
        journal.append("default", "deleted", 42, 3)
        journal.append("default", "content_changed", 42, 1)
        journal.append("default", "new", 42, 4) // no facts: deleted before classification
        journal.tailCursor("default")
        Thread.sleep(350)
        val result = waiter.await("default", request(cursor)) { true }
        assertEquals("settled", result.status)
        assertEquals(listOf(1L), result.messages.map { it.messageId })
        assertEquals(5, result.skippedChanges)
        assertFalse(result.messages.single().addressed)
        assertEquals(emptyList(), result.messages.single().addressedBy)
    }

    @Test fun `an already quiet backlog settles at once and an empty wait times out with a fresh cursor`() {
        val journal = journal()
        val waiter = SettledMessageWaiter(journal, client)
        val cursor = journal.tailCursor("default")
        journal.message(42, 1, mention = true)
        journal.message(43, 2, reply = true)
        journal.tailCursor("default")
        Thread.sleep(250)
        val started = System.nanoTime()
        val result = waiter.await("default", request(cursor, settleMillis = 200)) { true }
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1_000)
        assertEquals("settled", result.status)
        assertEquals(listOf(42L, 43L), result.chats.map { it.chatId })
        assertEquals(listOf(listOf("mention"), listOf("reply")), result.messages.map { it.addressedBy })
        val empty = waiter.await("default", request(result.nextCursor, timeoutSeconds = 0)) { true }
        assertEquals("timed_out", empty.status)
        assertTrue(empty.timedOut && empty.messages.isEmpty() && empty.pendingMessages == 0)
        assertEquals(result.nextCursor, empty.nextCursor)
        // An omitted cursor starts at the tail and never replays the backlog.
        assertEquals("timed_out", waiter.await("default", request(null, timeoutSeconds = 0)) { true }.status)
    }

    @Test fun `a timeout in the middle of a burst returns nothing and rewinds so the next call gets the whole burst`() {
        val journal = journal()
        val waiter = SettledMessageWaiter(journal, client)
        journal.message(42, 1) // before the cursor: never returned
        val cursor = journal.tailCursor("default")
        journal.append("default", "edited", 42, 1)
        journal.message(42, 2)
        journal.message(42, 3)
        journal.tailCursor("default")
        val first = waiter.await("default", request(cursor, settleMillis = 3_000, timeoutSeconds = 1)) { true }
        assertEquals("timed_out", first.status)
        assertTrue(first.messages.isEmpty() && first.chats.isEmpty())
        assertEquals(2, first.pendingMessages)
        assertFalse(first.settled)
        // The cursor stops just before the first pending message, past the skipped edit.
        val epoch = cursor.substringBefore(':')
        assertEquals("$epoch:${cursor.substringAfter(':').toLong() + 1}", first.nextCursor)
        journal.message(42, 4)
        Thread.sleep(250)
        val second = waiter.await("default", request(first.nextCursor, settleMillis = 200)) { true }
        assertEquals("settled", second.status)
        assertEquals(listOf(2L, 3L, 4L), second.messages.map { it.messageId })
    }

    @Test fun `addressed_only ignores unaddressed chatter so a busy group cannot hold the wait open`() {
        val journal = journal()
        val waiter = SettledMessageWaiter(journal, client)
        val cursor = journal.tailCursor("default")
        journal.message(-100, 1)
        journal.message(-100, 2, mention = true)
        journal.message(7, 3, private = true)
        journal.tailCursor("default")
        Thread.sleep(250)
        val result = waiter.await("default", request(cursor, settleMillis = 200, addressedOnly = true)) { true }
        assertEquals(listOf(2L, 3L), result.messages.map { it.messageId })
        assertTrue(result.messages.all { it.addressed })
        assertEquals(1, result.skippedChanges)
    }

    @Test fun `chat filter and guardrails decide what is classified and returned`() {
        val journal = journal()
        val waiter = SettledMessageWaiter(journal, client)
        val cursor = journal.tailCursor("default")
        journal.message(777000, 1, private = true)
        journal.message(42, 2, private = true)
        journal.message(43, 3, private = true)
        journal.tailCursor("default")
        Thread.sleep(250)
        val all = waiter.await("default", request(cursor, settleMillis = 200)) { it != 777000L }
        assertEquals(listOf(42L, 43L), all.messages.map { it.chatId })
        val one = waiter.await("default", request(cursor, settleMillis = 200, chatId = 43)) { it != 777000L }
        assertEquals(listOf(43L), one.messages.map { it.chatId })
        // A blocked chat's messages are never even looked up in TDLib.
        verify(exactly = 0) { client.getMessageAddressing(777000, any()) }
        verify(exactly = 1) { client.getMessageAddressing(42, any()) }
    }

    @Test fun `a coverage gap returns at once with what was collected and is not a reset`() {
        val journal = journal()
        val waiter = SettledMessageWaiter(journal, client)
        val cursor = journal.tailCursor("default")
        val wait = worker { waiter.await("default", request(cursor, settleMillis = 5_000, timeoutSeconds = 10)) { true } }
        awaitWaiting(wait.first)
        val classified = java.util.concurrent.CountDownLatch(1)
        every { client.getMessageAddressing(42, any()) } answers { classified.countDown(); listOf(facts[42L to 1L]!!) }
        journal.message(42, 1)
        assertTrue(classified.await(3, TimeUnit.SECONDS))
        // Messages collected from earlier pages are returned with the gap.
        journal.appendGap("default", ChangeJournal.REASON_RECONNECT)
        val result = wait.second.get(3, TimeUnit.SECONDS)
        assertEquals("gap", result.status)
        assertTrue(result.gap)
        assertFalse(result.reset || result.settled)
        assertEquals(listOf(ChangeJournal.REASON_RECONNECT), result.gapReasons)
        assertEquals(listOf(1L), result.messages.map { it.messageId })
        assertEquals(journal.tailCursor("default"), result.nextCursor)
    }

    @Test fun `a restart marker or a replaced journal is reported as a reset`() {
        val journal = journal()
        val waiter = SettledMessageWaiter(journal, client)
        val cursor = journal.tailCursor("default")
        journal.appendGap("default", ChangeJournal.REASON_RESTART)
        val restart = waiter.await("default", request(cursor)) { true }
        assertEquals("reset", restart.status)
        assertTrue(restart.reset && restart.gap)
        assertEquals(listOf(ChangeJournal.REASON_RESTART), restart.gapReasons)
        val replaced = waiter.await("default", request("00000000-0000-0000-0000-000000000000:0")) { true }
        assertEquals("reset", replaced.status)
        assertEquals(listOf(ChangeJournal.REASON_JOURNAL_REPLACED), replaced.gapReasons)
        assertEquals(journal.tailCursor("default"), replaced.nextCursor)
    }

    @Test fun `the limit returns early and the next call continues after it`() {
        val journal = journal()
        val waiter = SettledMessageWaiter(journal, client)
        val cursor = journal.tailCursor("default")
        (1L..5L).forEach { journal.message(42, it) }
        journal.tailCursor("default")
        val first = waiter.await("default", request(cursor, settleMillis = 10_000, limit = 3)) { true }
        assertEquals("limit", first.status)
        assertTrue(first.hasMore)
        assertFalse(first.settled)
        assertEquals(listOf(1L, 2L, 3L), first.messages.map { it.messageId })
        Thread.sleep(250)
        val rest = waiter.await("default", request(first.nextCursor, settleMillis = 200)) { true }
        assertEquals(listOf(4L, 5L), rest.messages.map { it.messageId })
        // At the end of the journal a limit result says there is nothing more.
        journal.message(42, 6)
        journal.message(42, 7)
        val exact = waiter.await("default", request(rest.nextCursor, settleMillis = 10_000, limit = 2)) { true }
        assertEquals("limit", exact.status)
        assertFalse(exact.hasMore)
    }

    @Test fun `the wait ends inside the tool deadline`() {
        val journal = journal()
        val waiter = SettledMessageWaiter(journal, client)
        assertTimeoutPreemptively(Duration.ofSeconds(2)) {
            ToolCallDeadline.run(Duration.ofMillis(200)) {
                val result = waiter.await("default", request(null, timeoutSeconds = 30)) { true }
                assertEquals("timed_out", result.status)
            }
        }
    }

    @Test fun `mcp cancellation ends a settled wait and releases its permit`() {
        val journal = journal(waiters = 1)
        val waiter = SettledMessageWaiter(journal, client)
        val key = "settled-wait"
        val wait = worker {
            McpCallCancellation.bind(key)
            try { waiter.await("default", request(null, timeoutSeconds = 30)) { true } } finally { McpCallCancellation.unbind() }
        }
        awaitWaiting(wait.first)
        assertFailsWith<TelegramBusyException> { waiter.await("default", request(null, timeoutSeconds = 0)) { true } }
        McpCallCancellation.cancel(key)
        assertFails { wait.second.get(3, TimeUnit.SECONDS) }
        assertEquals("timed_out", waiter.await("default", request(null, timeoutSeconds = 0)) { true }.status)
    }

    @Test fun `interruption cancels the wait`() {
        val journal = journal()
        val waiter = SettledMessageWaiter(journal, client)
        val wait = worker { waiter.await("default", request(null, timeoutSeconds = 30)) { true } }
        awaitWaiting(wait.first)
        wait.first.interrupt()
        val error = assertFailsWith<java.util.concurrent.ExecutionException> { wait.second.get(3, TimeUnit.SECONDS) }
        assertIs<TelegramApiException>(error.cause)
    }

    @Test fun `request bounds are validated`() {
        for (bad in listOf(
            { request(null, settleMillis = -1) }, { request(null, settleMillis = 20_001) },
            { request(null, timeoutSeconds = 31) }, { request(null, limit = 0) }, { request(null, limit = 201) },
        )) assertFailsWith<IllegalArgumentException> { bad() }
    }

    @Test fun `a message that arrives while the burst is being looked up is part of the burst, never split off`() {
        val journal = journal()
        val waiter = SettledMessageWaiter(journal, client)
        val cursor = journal.tailCursor("default")
        journal.message(42, 1)
        journal.message(42, 2, record = false)
        every { client.getMessageAddressing(42, listOf(1L)) } answers {
            Thread.sleep(400) // longer than the settle window
            journal.append("default", "new", 42, 2)
            listOf(facts[42L to 1L]!!)
        }
        val result = waiter.await("default", request(cursor, settleMillis = 200)) { true }
        assertEquals("settled", result.status)
        assertEquals(listOf(1L, 2L), result.messages.map { it.messageId })
    }

    @Test fun `a page with entries behind it is never declared settled when time runs out`() {
        val journal = journal()
        val waiter = SettledMessageWaiter(journal, client)
        val cursor = journal.tailCursor("default")
        journal.message(42, 1)
        Thread.sleep(300)
        repeat(60) { journal.append("default", "edited", 42, 1) }
        journal.message(42, 2)
        journal.tailCursor("default")
        // Scans read at most `limit` entries, so the first page ends among the edits.
        val cut = waiter.await("default", request(cursor, settleMillis = 200, timeoutSeconds = 0)) { true }
        assertEquals("timed_out", cut.status)
        assertTrue(cut.messages.isEmpty())
        assertEquals(1, cut.pendingMessages)
        assertTrue(cut.hasMore)
        assertEquals(cursor, cut.nextCursor)
        val whole = waiter.await("default", request(cut.nextCursor, settleMillis = 200)) { true }
        assertEquals("settled", whole.status)
        assertEquals(listOf(1L, 2L), whole.messages.map { it.messageId })
    }

    @Test fun `a confirming page with an outgoing lookup cannot hide a new incoming message`() {
        val journal = journal()
        val waiter = SettledMessageWaiter(journal, client)
        val cursor = journal.tailCursor("default")
        journal.message(42, 1)
        journal.tailCursor("default")
        every { client.getMessageAddressing(42, listOf(1L)) } answers {
            journal.message(42, 2, outgoing = true)
            journal.tailCursor("default")
            listOf(facts.getValue(42L to 1L))
        }
        every { client.getMessageAddressing(42, listOf(2L)) } answers {
            journal.message(42, 3)
            journal.tailCursor("default")
            listOf(facts.getValue(42L to 2L))
        }

        val result = waiter.await("default", request(cursor, settleMillis = 0)) { true }

        assertEquals("settled", result.status)
        assertEquals(listOf(1L, 3L), result.messages.map { it.messageId })
        assertEquals(listOf(SkippedChanges(SettledMessageWaiter.SKIP_OUTGOING, 1)), result.skippedReasons)
    }

    @Test fun `an already quiet backlog still settles with a zero timeout after one confirming scan`() {
        val journal = journal()
        val waiter = SettledMessageWaiter(journal, client)
        val cursor = journal.tailCursor("default")
        journal.message(42, 1)
        journal.tailCursor("default")
        Thread.sleep(250)
        val result = waiter.await("default", request(cursor, settleMillis = 200, timeoutSeconds = 0)) { true }
        assertEquals("settled", result.status)
        assertEquals(listOf(1L), result.messages.map { it.messageId })
    }

    @Test fun `lookups are bounded by the wait budget and exhaustion returns a resumable timeout`() {
        val journal = journal()
        val waiter = SettledMessageWaiter(journal, client)
        val cursor = journal.tailCursor("default")
        journal.message(42, 1)
        journal.tailCursor("default")
        val budgets = mutableListOf<Long?>()
        every { client.getMessageAddressing(42, any()) } answers {
            budgets += ToolCallDeadline.remainingNanos()
            // Like a TDLib wait: it ends when the capped tool deadline does.
            while ((ToolCallDeadline.remainingNanos() ?: Long.MAX_VALUE) > 0) Thread.sleep(10)
            throw TelegramTimeoutException("GetMessages", TelegramTimeoutException.Reason.TOOL_DEADLINE,
                requestSent = true, retryable = true, waitedMillis = 0)
        }
        val started = System.nanoTime()
        val result = assertTimeoutPreemptively(Duration.ofSeconds(3), org.junit.jupiter.api.function.ThrowingSupplier {
            waiter.await("default", request(cursor, settleMillis = 0, timeoutSeconds = 1)) { true }
        })
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1_800)
        assertTrue(budgets.single()!! <= TimeUnit.MILLISECONDS.toNanos(1_000), "lookup budget ${budgets.single()}")
        assertEquals("timed_out", result.status)
        assertTrue(result.hasMore)
        // Nothing was classified, so the cursor stays put and the next call sees the message.
        assertEquals(cursor, result.nextCursor)
        every { client.getMessageAddressing(42, any()) } answers { listOf(facts[42L to 1L]!!) }
        val retry = waiter.await("default", request(result.nextCursor, settleMillis = 0)) { true }
        assertEquals(listOf(1L), retry.messages.map { it.messageId })
    }

    @Test fun `a chat whose lookup fails is skipped with a reason and does not poison the cursor`() {
        val journal = journal()
        val waiter = SettledMessageWaiter(journal, client)
        val cursor = journal.tailCursor("default")
        journal.append("default", "new", -100, 1)
        journal.append("default", "new", -100, 2)
        journal.message(42, 3)
        journal.tailCursor("default")
        every { client.getMessageAddressing(-100, any()) } throws TelegramApiException(400, "Chat not found", readOnlyRequest = true)
        Thread.sleep(250)
        val result = waiter.await("default", request(cursor, settleMillis = 200)) { true }
        assertEquals("settled", result.status)
        assertEquals(listOf(3L), result.messages.map { it.messageId })
        assertEquals(2, result.skippedChanges)
        assertEquals(listOf(SkippedChanges(SettledMessageWaiter.SKIP_LOOKUP_FAILED, 2)), result.skippedReasons)
        assertEquals(journal.tailCursor("default"), result.nextCursor)
    }

    @Test fun `account-wide lookup failures still fail the call without moving any cursor`() {
        val journal = journal()
        val waiter = SettledMessageWaiter(journal, client)
        val cursor = journal.tailCursor("default")
        journal.message(42, 1)
        every { client.getMessageAddressing(42, any()) } throws TelegramUnavailableException()
        assertFailsWith<TelegramUnavailableException> { waiter.await("default", request(cursor)) { true } }
        every { client.getMessageAddressing(42, any()) } throws TelegramApiException(429, "Too Many Requests: retry after 5")
        assertFailsWith<TelegramApiException> { waiter.await("default", request(cursor)) { true } }
    }

    @Test fun `a known gap returns without waiting on lookups for the same page`() {
        val journal = journal()
        val waiter = SettledMessageWaiter(journal, client)
        val cursor = journal.tailCursor("default")
        journal.message(42, 1)
        journal.appendGap("default", ChangeJournal.REASON_OVERFLOW)
        journal.tailCursor("default")
        every { client.getMessageAddressing(any(), any()) } answers { Thread.sleep(5_000); emptyList() }
        val started = System.nanoTime()
        val result = waiter.await("default", request(cursor)) { true }
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1_000)
        assertEquals("gap", result.status)
        assertEquals(listOf(SkippedChanges(SettledMessageWaiter.SKIP_BEHIND_GAP, 1)), result.skippedReasons)
        assertEquals(journal.tailCursor("default"), result.nextCursor)
        verify(exactly = 0) { client.getMessageAddressing(any(), any()) }
    }

    @Test fun `skipped entries are counted by reason`() {
        val journal = journal()
        val waiter = SettledMessageWaiter(journal, client)
        val cursor = journal.tailCursor("default")
        journal.message(42, 1, outgoing = true)
        journal.append("default", "deleted", 42, 1)
        journal.append("default", "new", 42, 2) // gone before classification
        journal.message(-100, 3)
        journal.message(7, 4, private = true)
        journal.tailCursor("default")
        Thread.sleep(250)
        val result = waiter.await("default", request(cursor, settleMillis = 200, addressedOnly = true)) { true }
        assertEquals(listOf(4L), result.messages.map { it.messageId })
        assertEquals(setOf(SkippedChanges(SettledMessageWaiter.SKIP_OUTGOING, 1), SkippedChanges(SettledMessageWaiter.SKIP_OTHER_CHANGE, 1),
            SkippedChanges(SettledMessageWaiter.SKIP_VANISHED, 1), SkippedChanges(SettledMessageWaiter.SKIP_NOT_ADDRESSED, 1)),
            result.skippedReasons.toSet())
        assertEquals(4, result.skippedChanges)
    }

    @Test fun `a closed client fails the call instead of skipping messages`() {
        val journal = journal()
        val waiter = SettledMessageWaiter(journal, client)
        val cursor = journal.tailCursor("default")
        journal.message(42, 1)
        journal.tailCursor("default")
        every { client.getMessageAddressing(42, any()) } throws IllegalStateException("Client is closed")
        assertFailsWith<IllegalStateException> { waiter.await("default", request(cursor, settleMillis = 0)) { true } }
        // Nothing moved: once the client is back, the same cursor still yields the message.
        every { client.getMessageAddressing(42, any()) } answers { listOf(facts[42L to 1L]!!) }
        val retry = waiter.await("default", request(cursor, settleMillis = 0)) { true }
        assertEquals(listOf(1L), retry.messages.map { it.messageId })
        assertTrue(retry.skippedReasons.isEmpty())
    }

    @Test fun `the zero-timeout lookup floor never reaches past the tool-call deadline`() {
        val journal = journal()
        val waiter = SettledMessageWaiter(journal, client)
        assertEquals(SettledMessageWaiter.MIN_LOOKUP_NANOS, waiter.lookupBudget(0, immediatePoll = true))
        assertEquals(0L, waiter.lookupBudget(0, immediatePoll = false))
        assertEquals(TimeUnit.MILLISECONDS.toNanos(10),
            waiter.lookupBudget(TimeUnit.MILLISECONDS.toNanos(10), immediatePoll = false))
        val cursor = journal.tailCursor("default")
        journal.message(42, 1)
        journal.tailCursor("default")
        val budgets = mutableListOf<Long?>()
        every { client.getMessageAddressing(42, any()) } answers {
            budgets += ToolCallDeadline.remainingNanos()
            while ((ToolCallDeadline.remainingNanos() ?: Long.MAX_VALUE) > 0) Thread.sleep(5)
            throw TelegramTimeoutException("GetMessages", TelegramTimeoutException.Reason.TOOL_DEADLINE,
                requestSent = true, retryable = true, waitedMillis = 0)
        }
        val started = System.nanoTime()
        val result = ToolCallDeadline.run(Duration.ofMillis(150)) {
            assertTrue(waiter.lookupBudget(0, immediatePoll = true) <= TimeUnit.MILLISECONDS.toNanos(100))
            waiter.await("default", request(cursor, settleMillis = 0, timeoutSeconds = 0)) { true }
        }
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        // The lookup got at most the outer deadline less its reserve, not the 250 ms floor.
        assertTrue(budgets.single()!! <= TimeUnit.MILLISECONDS.toNanos(100), "lookup budget ${budgets.single()}")
        assertTrue(elapsed < 250, "took $elapsed ms")
        assertEquals("timed_out", result.status)
        assertEquals(cursor, result.nextCursor)
    }
}
