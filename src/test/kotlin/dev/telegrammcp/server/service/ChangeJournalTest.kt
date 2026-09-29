package dev.telegrammcp.server.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.*
import it.tdlight.jni.TdApi
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.StandardOpenOption.APPEND
import java.time.Duration
import java.util.concurrent.CountDownLatch
import kotlin.test.*

class ChangeJournalTest {
    @TempDir lateinit var root: Path
    private val mapper = jacksonObjectMapper()
    private fun journal(queueCapacity: Int = ChangeJournal.QUEUE_CAPACITY): ChangeJournal {
        val paths = mockk<PlatformPaths>()
        every { paths.applicationDataDirectory } answers { root }
        return ChangeJournal(paths, mapper, queueCapacity)
    }
    private fun ChangeJournal.readAll(cursor: String): Pair<List<ChangePage>, String> {
        val pages = mutableListOf<ChangePage>()
        var next = cursor
        do {
            val page = page("default", next, 200) { true }
            pages += page
            next = page.nextCursor
        } while (page.hasMore)
        return pages to next
    }

    @Test fun `outgoing changes contain only the final hydratable message ID`() {
        val journal = journal()
        val initial = journal.page("default", null, 10) { true }
        journal.recordNewMessage("default", TdApi.Message().apply {
            chatId = 42; id = 10; sendingState = TdApi.MessageSendingStatePending()
        })
        assertTrue(journal.page("default", initial.nextCursor, 10) { true }.entries.isEmpty())
        journal.recordNewMessage("default", TdApi.Message().apply { chatId = 42; id = 20 })
        val entry = journal.page("default", initial.nextCursor, 10) { true }.entries.single()
        assertEquals(20L, entry.messageId)
        assertEquals("new", entry.kind)
    }

    @Test fun `restart preserves cursor and revoked chats are filtered without stalling`() {
        val journal = journal()
        journal.append("default", "new", 42, 1)
        journal.append("default", "edited", 99, 2)
        journal.append("default", "deleted", 42, 1)
        val page = journal.page("default", null, 1) { it == 42L }
        assertTrue(page.hasMore)
        val hidden = journal().page("default", page.nextCursor, 1) { it == 42L }
        assertTrue(hidden.entries.isEmpty())
        assertTrue(hidden.hasMore)
        val last = journal().page("default", hidden.nextCursor, 1) { it == 42L }
        assertEquals("deleted", last.entries.single().kind)
        assertFalse(last.hasMore)
        assertFailsWith<IllegalArgumentException> { journal.page("other", last.nextCursor, 10) { true } }
    }

    @Test fun `coverage gaps and expired cursors are explicit`() {
        val journal = journal()
        journal.append("default", "coverage_gap")
        assertTrue(journal.page("default", null, 100) { true }.gap)
        val page = journal.page("default", null, 100) { true }
        journal.append("default", "new", 42, 3)
        assertFalse(journal.page("default", page.nextCursor, 100) { true }.gap)
        assertFailsWith<IllegalArgumentException> { journal.page("default", page.nextCursor.substringBefore(':') + ":999", 10) { true } }
    }

    @Test fun `empty journal cursor survives restart and retention loss is visible`() {
        val first = journal().page("default", null, 10) { true }
        assertEquals(first.nextCursor, journal().page("default", first.nextCursor, 10) { true }.nextCursor)
        val epoch = first.nextCursor.substringBefore(':')
        Files.writeString(root.resolve("changes/default.jsonl"),
            mapper.writeValueAsString(ChangeJournalHeader(ChangeJournal.FORMAT, epoch)) + "\n" +
            mapper.writeValueAsString(ChangeEntry(10001, "new", 42, 100, "2026-09-28T00:00:00Z")) + "\n")
        val page = journal().page("default", first.nextCursor, 10) { true }
        assertTrue(page.gap)
        assertEquals(10001L, page.entries.single().sequence)
    }

    @Test fun `failed update is reported as a gap and survives the next successful write`() {
        val journal = journal()
        val initial = journal.page("default", null, 10) { true }
        mockkObject(DurableFiles)
        try {
            every { DurableFiles.append(any(), any()) } throws java.io.IOException("disk unavailable")
            journal.append("default", "new", 42, 1)
            assertTrue(journal.page("default", initial.nextCursor, 10) { true }.gap)
        } finally { unmockkObject(DurableFiles) }
        journal.append("default", "new", 42, 2)
        val page = journal().page("default", initial.nextCursor, 10) { true }
        assertTrue(page.gap)
        assertEquals(listOf("coverage_gap", "new"), page.entries.map { it.kind })
    }

    @Test fun `update handlers never wait for the disk and bursts are batched`() {
        val journal = journal()
        val initial = journal.page("default", null, 10) { true }
        val release = CountDownLatch(1)
        mockkObject(DurableFiles)
        try {
            every { DurableFiles.append(any(), any()) } answers { release.await(); callOriginal() }
            // Synchronous persistence would hang here: the first write waits for the latch.
            assertTimeoutPreemptively(Duration.ofSeconds(10)) {
                repeat(1_000) { journal.append("default", "new", 42, it + 1L) }
            }
            release.countDown()
            val (pages, _) = journal.readAll(initial.nextCursor)
            assertTrue(pages.none { it.gap })
            assertEquals((1L..1_000L).toList(), pages.flatMap { it.entries }.map { it.messageId })
            verify(atMost = 3) { DurableFiles.append(any(), any()) }
        } finally { unmockkObject(DurableFiles) }
    }

    @Test fun `queue overflow is reported after the retained updates`() {
        val journal = journal(queueCapacity = 100)
        val initial = journal.page("default", null, 10) { true }
        val release = CountDownLatch(1)
        mockkObject(DurableFiles)
        try {
            every { DurableFiles.append(any(), any()) } answers { release.await(); callOriginal() }
            repeat(1_000) { journal.append("default", "new", 42, it + 1L) }
            release.countDown()
            val (pages, cursor) = journal.readAll(initial.nextCursor)
            val entries = pages.flatMap { it.entries }
            assertTrue(pages.any { it.gap })
            assertEquals(1, entries.count { it.kind == "coverage_gap" })
            val kept = entries.filter { it.kind == "new" }.map { it.messageId!! }
            assertTrue(kept.size in 1..<1_000)
            assertEquals(kept.sorted(), kept)
            journal.append("default", "new", 42, 5_000)
            assertFalse(journal.page("default", cursor, 10) { true }.gap)
        } finally { unmockkObject(DurableFiles) }
    }

    @Test fun `interrupted final append is dropped while malformed history fails closed`() {
        val first = journal().page("default", null, 10) { true }
        val file = root.resolve("changes/default.jsonl")
        Files.writeString(file, mapper.writeValueAsString(ChangeEntry(1, "new", 42, 7, "2026-09-28T00:00:00Z")) +
            "\n{\"sequence\":2,\"ki", APPEND)
        val journal = journal()
        assertEquals(listOf(1L), journal.page("default", first.nextCursor, 10) { true }.entries.map { it.sequence })
        journal.append("default", "new", 42, 8)
        assertEquals(listOf(1L, 2L), journal.page("default", first.nextCursor, 10) { true }.entries.map { it.sequence })
        assertEquals(3, Files.readAllLines(file).size)

        Files.writeString(file, "not json\n", APPEND)
        assertFailsWith<IllegalStateException> { journal().page("default", null, 10) { true } }
    }

    @Test fun `compaction keeps the latest records and existing cursors`() {
        val journal = journal()
        val initial = journal.page("default", null, 10) { true }
        repeat(6) { chunk ->
            repeat(3_500) { journal.append("default", "new", 42, chunk * 3_500L + it + 1) }
            journal.page("default", null, 1) { true } // waits for the writer
        }
        val file = root.resolve("changes/default.jsonl")
        assertTrue(Files.readAllLines(file).size <= 2 * ChangeJournal.RETAINED + 1)
        val restarted = journal()
        val expired = restarted.page("default", initial.nextCursor, 10) { true }
        assertTrue(expired.gap)
        assertEquals(21_000L - ChangeJournal.RETAINED + 1, expired.entries.first().sequence)
        val recent = restarted.page("default", initial.nextCursor.substringBefore(':') + ":20990", 100) { true }
        assertFalse(recent.gap)
        assertFalse(recent.hasMore)
        assertEquals((20_991L..21_000L).toList(), recent.entries.map { it.sequence })
    }
}
