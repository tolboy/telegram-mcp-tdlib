package dev.telegrammcp.server.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.client.ToolCallDeadline
import dev.telegrammcp.server.exception.TelegramApiException
import dev.telegrammcp.server.exception.TelegramBusyException
import dev.telegrammcp.server.exception.TelegramErrorKind
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.*

class ChangeJournalWaitTest {
    @TempDir lateinit var root: Path
    private val journals = mutableListOf<ChangeJournal>()
    private val threads = mutableListOf<Thread>()
    @AfterEach fun close() {
        threads.forEach(Thread::interrupt)
        threads.forEach { it.join(2_000) }
        journals.forEach(ChangeJournal::close)
    }

    private fun journal(waiters: Int = ChangeJournal.MAX_WAITERS_PER_ACCOUNT): ChangeJournal {
        val paths = mockk<PlatformPaths>()
        every { paths.applicationDataDirectory } returns root
        return ChangeJournal(paths, jacksonObjectMapper(), maxWaitersPerAccount = waiters).also { journals += it }
    }

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

    @Test fun `concurrent waiters receive the same later durable change without consuming it`() {
        val journal = journal()
        val cursor = journal.page("default", null, 10) { true }.nextCursor
        val first = worker { journal.waitForChanges("default", cursor, 10, Duration.ofSeconds(3)) { true } }
        val second = worker { journal.waitForChanges("default", cursor, 10, Duration.ofSeconds(3)) { true } }
        awaitWaiting(first.first)
        awaitWaiting(second.first)
        journal.append("default", "new", 42, 100)
        val a = first.second.get(3, TimeUnit.SECONDS)
        val b = second.second.get(3, TimeUnit.SECONDS)
        assertFalse(a.timedOut)
        assertEquals(listOf(100L), a.entries.map { it.messageId })
        assertEquals(a, b)
        assertEquals(a.nextCursor, journal.page("default", cursor, 10) { true }.nextCursor)
    }

    @Test fun `omitting cursor starts at the tail and zero timeout does not replay history`() {
        val journal = journal()
        journal.append("default", "new", 42, 100)
        val existing = journal.page("default", null, 10) { true }
        val result = journal.waitForChanges("default", null, 10, Duration.ZERO) { true }
        assertTrue(result.timedOut)
        assertTrue(result.entries.isEmpty())
        assertEquals(existing.nextCursor, result.nextCursor)
    }

    @Test fun `hidden changes and another account cannot complete a wait`() {
        val journal = journal()
        val cursor = journal.page("default", null, 10) { true }.nextCursor
        val wait = worker { journal.waitForChanges("default", cursor, 1, Duration.ofSeconds(3)) { it == 42L } }
        awaitWaiting(wait.first)
        journal.append("other", "new", 42, 300)
        journal.append("default", "new", 99, 200)
        journal.page("default", cursor, 10) { true } // commits the hidden update
        assertFalse(wait.second.isDone)
        journal.append("default", "edited", 42, 100)
        val result = wait.second.get(3, TimeUnit.SECONDS)
        assertEquals(listOf(100L), result.entries.map { it.messageId })
        assertFalse(result.timedOut)
        assertTrue(result.nextCursor.endsWith(":2"))
    }

    @Test fun `hidden backlog advances the timeout cursor without rescanning`() {
        val journal = journal()
        val cursor = journal.page("default", null, 10) { true }.nextCursor
        repeat(100) { journal.append("default", "new", 99, it + 1L) }
        val tail = journal.page("default", null, 200) { true }.nextCursor
        var checked = 0
        val result = journal.waitForChanges("default", cursor, 10, Duration.ofMillis(100)) { checked++; false }
        assertTrue(result.timedOut)
        assertTrue(result.entries.isEmpty())
        assertEquals(tail, result.nextCursor)
        assertEquals(100, checked)
    }

    @Test fun `coverage gaps return immediately and invalid account cursors fail closed`() {
        val journal = journal()
        val cursor = journal.page("default", null, 10) { true }.nextCursor
        journal.append("default", "coverage_gap")
        val result = journal.waitForChanges("default", cursor, 10, Duration.ofSeconds(30)) { false }
        assertTrue(result.gap)
        assertFalse(result.timedOut)
        assertEquals("coverage_gap", result.entries.single().kind)
        assertFailsWith<IllegalArgumentException> {
            journal.waitForChanges("other", cursor, 10, Duration.ZERO) { true }
        }
        assertFailsWith<IllegalArgumentException> {
            journal.waitForChanges("default", cursor.substringBefore(':') + ":999", 10, Duration.ZERO) { true }
        }
    }

    @Test fun `wait follows the remaining tool deadline`() {
        val journal = journal()
        assertTimeoutPreemptively(Duration.ofSeconds(2)) {
            ToolCallDeadline.run(Duration.ofMillis(150)) {
                val result = journal.waitForChanges("default", null, 10, Duration.ofSeconds(30)) { true }
                assertTrue(result.timedOut)
                assertTrue(result.entries.isEmpty())
            }
        }
    }

    @Test fun `interruption releases a full account wait budget and preserves cancellation`() {
        val journal = journal(waiters = 1)
        val cursor = journal.page("default", null, 10) { true }.nextCursor
        val wait = worker {
            try { journal.waitForChanges("default", cursor, 10, Duration.ofSeconds(30)) { true } }
            catch (error: TelegramApiException) {
                assertTrue(Thread.currentThread().isInterrupted)
                throw error
            }
        }
        awaitWaiting(wait.first)
        assertFailsWith<TelegramBusyException> {
            journal.waitForChanges("default", cursor, 10, Duration.ZERO) { true }
        }
        // Another account has its own independent waiter capacity.
        assertTrue(journal.waitForChanges("other", null, 10, Duration.ZERO) { true }.timedOut)
        wait.first.interrupt()
        val error = assertFailsWith<ExecutionException> { wait.second.get(3, TimeUnit.SECONDS) }
        assertIs<TelegramApiException>(error.cause)
        assertEquals(TelegramErrorKind.CANCELLED, (error.cause as TelegramApiException).kind)
        assertTrue(journal.waitForChanges("default", cursor, 10, Duration.ZERO) { true }.timedOut)
    }

    @Test fun `closing the journal wakes long polls`() {
        val journal = journal()
        val wait = worker { journal.waitForChanges("default", null, 10, Duration.ofSeconds(30)) { true } }
        awaitWaiting(wait.first)
        journal.close()
        val error = assertFailsWith<ExecutionException> { wait.second.get(3, TimeUnit.SECONDS) }
        assertIs<TelegramApiException>(error.cause)
        assertEquals(TelegramErrorKind.CANCELLED, (error.cause as TelegramApiException).kind)
    }

    @Test fun `a second journal writer is observed without an in-process signal`() {
        val journal = journal()
        val cursor = journal.page("default", null, 10) { true }.nextCursor
        val wait = worker { journal.waitForChanges("default", cursor, 10, Duration.ofSeconds(3)) { true } }
        awaitWaiting(wait.first)
        val other = journal()
        other.append("default", "new", 42, 100)
        other.page("default", cursor, 10) { true }
        val result = wait.second.get(3, TimeUnit.SECONDS)
        assertFalse(result.timedOut)
        assertEquals(100L, result.entries.single().messageId)
    }
}
