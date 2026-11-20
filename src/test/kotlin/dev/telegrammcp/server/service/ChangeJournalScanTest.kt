package dev.telegrammcp.server.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.time.Duration
import org.junit.jupiter.api.assertTimeoutPreemptively
import kotlin.test.*

class ChangeJournalScanTest {
    @TempDir lateinit var root: Path
    private val journals = mutableListOf<ChangeJournal>()
    @AfterEach fun close() { journals.forEach(ChangeJournal::close) }

    private fun journal(queueCapacity: Int = ChangeJournal.QUEUE_CAPACITY): ChangeJournal {
        val paths = mockk<PlatformPaths>()
        every { paths.applicationDataDirectory } returns root
        return ChangeJournal(paths, jacksonObjectMapper(), queueCapacity).also { journals += it }
    }

    @Test fun `scan names the cause of each marker and only session starts count as a reset`() {
        val journal = journal()
        val start = journal.tailCursor("default")
        journal.append("default", "new", 42, 1)
        journal.appendGap("default", ChangeJournal.REASON_RECONNECT)
        val reconnect = assertNotNull(journal.scan("default", start, 200, { true }))
        assertEquals(listOf(ChangeJournal.REASON_RECONNECT), reconnect.gapReasons)
        assertTrue(reconnect.gap)
        assertFalse(reconnect.reset)
        assertEquals(listOf(42L), reconnect.entries.mapNotNull { it.chatId })

        for (reason in listOf(ChangeJournal.REASON_RESTART, ChangeJournal.REASON_SESSION_ACCEPTED)) {
            val cursor = journal.tailCursor("default")
            journal.appendGap("default", reason)
            val scan = assertNotNull(journal.scan("default", cursor, 200, { true }))
            assertEquals(listOf(reason), scan.gapReasons)
            assertTrue(scan.reset, reason)
        }
        // The persisted format is unchanged: markers stay plain coverage gaps for changes_since.
        val page = journal.page("default", start, 200) { true }
        assertTrue(page.gap)
        assertEquals(3, page.entries.count { it.kind == ChangeJournal.GAP })
        assertFalse(Files.readString(root.resolve("changes/default.jsonl")).contains("reason"))
    }

    @Test fun `scans after the markers report no gap and the filter still advances the cursor`() {
        val journal = journal()
        journal.appendGap("default", ChangeJournal.REASON_RESTART)
        val cursor = journal.tailCursor("default")
        journal.append("default", "new", 7, 1)
        journal.append("default", "new", 42, 2)
        val scan = assertNotNull(journal.scan("default", cursor, 200, { it == 42L }))
        assertFalse(scan.gap)
        assertEquals(listOf(2L), scan.entries.map { it.messageId })
        assertEquals(journal.tailCursor("default"), scan.nextCursor)
    }

    @Test fun `a marker written by another process is a gap of unknown cause`() {
        val first = journal()
        val cursor = first.tailCursor("default")
        val second = journal()
        second.appendGap("default", ChangeJournal.REASON_RESTART)
        second.tailCursor("default")
        val scan = assertNotNull(first.scan("default", cursor, 200, { true }))
        assertEquals(listOf(ChangeJournal.REASON_UNKNOWN), scan.gapReasons)
        assertFalse(scan.reset)
        // The writing process itself knows the cause.
        assertEquals(listOf(ChangeJournal.REASON_RESTART), assertNotNull(second.scan("default", cursor, 200, { true })).gapReasons)
    }

    @Test fun `a cursor from a replaced journal resets to the new tail while page keeps rejecting it`() {
        val journal = journal()
        journal.append("default", "new", 42, 1)
        val old = journal.tailCursor("default")
        journal.close()
        Files.delete(root.resolve("changes/default.jsonl"))
        val fresh = journal()
        fresh.append("default", "new", 42, 2)
        val scan = assertNotNull(fresh.scan("default", old, 200, { true }))
        assertEquals(listOf(ChangeJournal.REASON_JOURNAL_REPLACED), scan.gapReasons)
        assertTrue(scan.reset)
        assertTrue(scan.entries.isEmpty())
        assertEquals(fresh.tailCursor("default"), scan.nextCursor)
        assertFailsWith<IllegalArgumentException> { fresh.page("default", old, 10) { true } }
        // Malformed and future cursors are still errors, never a silent reset.
        val epoch = scan.nextCursor.substringBefore(':')
        val oldEpoch = UUID.randomUUID()
        for (bad in listOf("nonsense", ":5", "not-an-epoch:1", "$epoch:999", "$epoch:x",
            "$oldEpoch:x", "$oldEpoch:-1", "$oldEpoch:", "0-0-0-0-0:1")) {
            assertFailsWith<IllegalArgumentException>(bad) { fresh.scan("default", bad, 10, { true }) }
        }
    }

    @Test fun `an immediate scan respects a held store lock`() {
        val journal = journal()
        val cursor = journal.tailCursor("default")
        FileChannel.open(root.resolve("changes/store.lock"), StandardOpenOption.WRITE).use { file ->
            file.lock().use {
                assertTimeoutPreemptively(Duration.ofSeconds(2)) {
                    assertNull(journal.scan("default", cursor, 10, { true }, budgetNanos = 0))
                }
            }
        }
        val scan = assertNotNull(journal.scan("default", cursor, 10, { true }))
        assertEquals(cursor, scan.nextCursor)
        assertTrue(scan.writerCaughtUp)
    }

    @Test fun `a scan reports a pending update enqueued after the writer flush`() {
        val journal = journal()
        val cursor = journal.tailCursor("default")
        journal.append("default", "new", 42, 1)

        val scan = assertNotNull(journal.scan("default", cursor, 10, {
            journal.append("default", "new", 42, 2)
            true
        }))

        assertEquals(listOf(1L), scan.entries.map { it.messageId })
        assertFalse(scan.hasMore)
        assertFalse(scan.writerCaughtUp)
        val followUp = assertNotNull(journal.scan("default", scan.nextCursor, 10, { true }))
        assertEquals(listOf(2L), followUp.entries.map { it.messageId })
        assertTrue(followUp.writerCaughtUp)
    }

    @Test fun `a pending update on another account does not keep the scan open`() {
        val journal = journal()
        val cursor = journal.tailCursor("default")
        journal.append("default", "new", 42, 1)

        val scan = assertNotNull(journal.scan("default", cursor, 10, {
            journal.append("another", "new", 42, 2)
            true
        }))

        assertEquals(listOf(1L), scan.entries.map { it.messageId })
        assertTrue(scan.writerCaughtUp)
    }

    @Test fun `a replaced journal with the same file size resets a warm reader`() {
        val reader = journal()
        val oldCursor = reader.tailCursor("default")
        val path = root.resolve("changes/default.jsonl")
        val previousSize = Files.size(path)
        Files.delete(path)
        val newCursor = journal().tailCursor("default")
        assertEquals(previousSize, Files.size(path))

        val scan = assertNotNull(reader.scan("default", oldCursor, 10, { true }))
        assertTrue(scan.reset)
        assertEquals(newCursor, scan.nextCursor)
    }

    @Test fun `a cursor older than the retained window reports retention`() {
        val journal = journal()
        val cursor = journal.tailCursor("default")
        repeat(ChangeJournal.RETAINED + 5) { journal.append("default", "new", 42, it + 1L) }
        journal.tailCursor("default")
        val scan = assertNotNull(journal.scan("default", cursor, 10, { true }))
        assertEquals(listOf(ChangeJournal.REASON_RETENTION), scan.gapReasons)
        assertFalse(scan.reset)
    }

    @Test fun `queue overflow is reported as overflow`() {
        val journal = journal(queueCapacity = 10)
        val cursor = journal.tailCursor("default")
        val release = CountDownLatch(1)
        val writing = CountDownLatch(1)
        mockkObject(DurableFiles)
        try {
            every { DurableFiles.append(any(), any()) } answers { writing.countDown(); release.await(); callOriginal() }
            journal.append("default", "new", 42, 1)
            assertTrue(writing.await(10, TimeUnit.SECONDS))
            repeat(50) { journal.append("default", "new", 42, it + 2L) }
            release.countDown()
            var reasons = emptyList<String>()
            var next = cursor
            do {
                val scan = assertNotNull(journal.scan("default", next, 200, { true }))
                reasons = reasons + scan.gapReasons
                next = scan.nextCursor
            } while (scan.hasMore)
            assertTrue(ChangeJournal.REASON_OVERFLOW in reasons, reasons.toString())
            assertFalse(ChangeJournal.REASON_UNKNOWN in reasons, reasons.toString())
        } finally { release.countDown(); unmockkObject(DurableFiles) }
    }

    @Test fun `the waiter permit is shared with wait_for_changes`() {
        val paths = mockk<PlatformPaths>()
        every { paths.applicationDataDirectory } returns root
        val journal = ChangeJournal(paths, jacksonObjectMapper(), maxWaitersPerAccount = 1).also { journals += it }
        journal.withWaiterPermit("default") {
            assertFailsWith<dev.telegrammcp.server.exception.TelegramBusyException> {
                journal.waitForChanges("default", null, 10, java.time.Duration.ZERO) { true }
            }
            // Another account keeps its own capacity.
            assertTrue(journal.withWaiterPermit("other") { true })
        }
        assertTrue(journal.waitForChanges("default", null, 10, java.time.Duration.ZERO) { true }.timedOut)
    }
}
