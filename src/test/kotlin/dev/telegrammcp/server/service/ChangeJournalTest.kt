package dev.telegrammcp.server.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Files
import kotlin.test.*

class ChangeJournalTest {
    @TempDir lateinit var root: Path
    private fun journal(): ChangeJournal {
        val paths = mockk<PlatformPaths>()
        every { paths.applicationDataDirectory } answers { root }
        return ChangeJournal(paths, jacksonObjectMapper())
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
        val retained = ChangeState(epoch, 10001, listOf(ChangeEntry(10001, "new", 42, 100, "2026-09-28T00:00:00Z")))
        Files.write(root.resolve("changes/default.json"), jacksonObjectMapper().writeValueAsBytes(retained))
        val page = journal().page("default", first.nextCursor, 10) { true }
        assertTrue(page.gap)
        assertEquals(10001L, page.entries.single().sequence)
    }

    @Test fun `failed update is reported as a gap and survives the next successful write`() {
        val journal = journal()
        val initial = journal.page("default", null, 10) { true }
        mockkObject(DurableFiles)
        try {
            every { DurableFiles.write(any(), any()) } throws java.io.IOException("disk unavailable")
            assertFailsWith<java.io.IOException> { journal.append("default", "new", 42, 1) }
        } finally { unmockkObject(DurableFiles) }
        assertTrue(journal.page("default", initial.nextCursor, 10) { true }.gap)
        journal.append("default", "new", 42, 2)
        assertTrue(journal().page("default", initial.nextCursor, 10) { true }.gap)
    }
}
