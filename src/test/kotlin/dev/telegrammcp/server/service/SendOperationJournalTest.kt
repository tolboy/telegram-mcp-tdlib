package dev.telegrammcp.server.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.exception.SendJournalException
import dev.telegrammcp.server.exception.SendOperationBusyException
import dev.telegrammcp.server.exception.SendOperationConflictException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class SendOperationJournalTest {
    @TempDir lateinit var directory: Path
    private fun journal() = SendOperationJournal(directory, jacksonObjectMapper())

    @Test
    fun `late receipt survives reopen without changing operation identity or resending`() {
        val store = journal()
        assertFailsWith<IllegalStateException> {
            store.send("work", "key", 42, "payload", {}) { error("timeout") }
        }
        val unknown = store.status("work", "key", 42)
        assertEquals("UNKNOWN", store.recoverLate("work", "key", 42, unknown)["status"])
        store.recordLate("work", "key", 42, 120)
        val reopened = journal()
        val replay = reopened.send("work", "key", 42, "payload", { fail("quota") }) { fail("duplicate") }
        val recovered = reopened.recoverLate("work", "key", 42, replay)
        assertEquals("SENT", recovered["status"])
        assertEquals(120L, recovered["message_id"])
        assertEquals(unknown["operation_id"], recovered["operation_id"])
        assertEquals(true, recovered["replayed"])
        assertEquals("UNKNOWN", reopened.recoverLate("personal", "key", 42, unknown)["status"])
    }

    @Test
    fun `receipt survives restart without a second send or quota charge`() {
        var sends = 0
        var charges = 0
        val first = journal().send("work", "key", 42, "fingerprint", { charges++ }) { sends++; 100 }
        val replay = journal().send("work", "key", 42, "fingerprint", { charges++ }) { sends++; 200 }
        assertEquals("SENT", first["status"])
        assertEquals(100L, replay["message_id"])
        assertEquals(true, replay["replayed"])
        assertEquals(1, sends)
        assertEquals(1, charges)
        assertEquals("SENT", journal().status("work", "key", 42)["status"])
    }

    @Test
    fun `reservation is durable before sending and crash stays unknown`() {
        val store = journal()
        assertFailsWith<IllegalStateException> {
            store.send("work", "key", 42, "fingerprint", {}) {
                Files.walk(directory).use { paths ->
                    val entry = paths.filter { it.toString().endsWith(".jsonl") }.findFirst().orElseThrow()
                    assertTrue(Files.readString(entry).contains("fingerprint"))
                }
                error("simulated process loss after dispatch")
            }
        }
        val replay = journal().send("work", "key", 42, "fingerprint", { fail("must not charge") }) { fail("must not send") }
        assertEquals("UNKNOWN", replay["status"])
        assertNull(replay["message_id"])
    }

    @Test
    fun `key conflicts do not disclose another chats receipt`() {
        val store = journal()
        store.send("work", "key", 42, "first", {}) { 100 }
        assertFailsWith<SendOperationConflictException> {
            store.send("work", "key", 42, "changed", {}) { fail("must not send") }
        }
        assertFailsWith<SendOperationConflictException> { store.status("work", "key", 43) }
        assertEquals("NOT_FOUND", store.status("personal", "key", 42)["status"])
        assertEquals(200L, store.send("personal", "key", 42, "first", {}) { 200 }["message_id"])
    }

    @Test
    fun `concurrent journal instances permit only one dispatch`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = CompletableFuture.supplyAsync {
            journal().send("work", "key", 42, "fingerprint", {}) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                100
            }
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertFailsWith<SendOperationBusyException> {
                journal().send("work", "key", 42, "fingerprint", {}) { fail("duplicate send") }
            }
        } finally { release.countDown() }
        assertEquals("SENT", first.get(5, TimeUnit.SECONDS)["status"])
    }

    @Test
    fun `blocked preflight does not reserve the key`() {
        val store = journal()
        assertFailsWith<IllegalStateException> {
            store.send("work", "key", 42, "fingerprint", { error("policy denied") }) { fail("must not send") }
        }
        assertEquals("NOT_FOUND", store.status("work", "key", 42)["status"])
        assertEquals("SENT", store.send("work", "key", 42, "fingerprint", {}) { 100 }["status"])
    }

    @Test
    fun `corrupt receipt fails closed`() {
        val store = journal()
        store.send("work", "key", 42, "fingerprint", {}) { 100 }
        Files.walk(directory).use { paths ->
            val entry = paths.filter { it.toString().endsWith(".jsonl") }.findFirst().orElseThrow()
            Files.writeString(entry, "broken")
        }
        assertFailsWith<SendJournalException> {
            store.send("work", "key", 42, "fingerprint", {}) { fail("must not resend") }
        }
    }
}
