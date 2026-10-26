package dev.telegrammcp.server.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.exception.SendJournalException
import dev.telegrammcp.server.exception.SendOperationBusyException
import dev.telegrammcp.server.exception.SendOperationConflictException
import dev.telegrammcp.server.exception.TelegramApiException
import dev.telegrammcp.server.exception.TelegramFloodGateException
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

    @Test
    fun `keyed send hitting a flood wait is REJECTED and the same key sends on retry`() {
        val store = journal()
        var charges = 0
        val flood = TelegramApiException(429, "Too Many Requests: retry after 7")
        assertSame(flood, assertFailsWith<TelegramApiException> {
            store.send("work", "key", 42, "fingerprint", { charges++ }) { throw flood }
        })
        assertEquals("REJECTED", journal().status("work", "key", 42)["status"])

        // Local flood-gate rejection is recorded the same way.
        assertFailsWith<TelegramFloodGateException> {
            journal().send("work", "key", 42, "fingerprint", { charges++ }) { throw TelegramFloodGateException("chat 42", 3) }
        }
        assertEquals("REJECTED", journal().status("work", "key", 42)["status"])

        // A changed request under the same key is still a conflict.
        assertFailsWith<SendOperationConflictException> {
            journal().send("work", "key", 42, "other", {}) { fail("must not send") }
        }
        val sent = journal().send("work", "key", 42, "fingerprint", { charges++ }) { 77 }
        assertEquals("SENT", sent["status"])
        assertEquals(77L, sent["message_id"])
        assertEquals(false, sent["replayed"])
        assertEquals(3, charges, "beforeSend runs once per fresh attempt")
        assertEquals("SENT", journal().send("work", "key", 42, "fingerprint", { fail("quota") }) { fail("duplicate") }["status"])
    }

    @Test
    fun `non-flood failures and multi-message sends stay UNKNOWN`() {
        assertFailsWith<TelegramApiException> {
            journal().send("work", "a", 42, "fp", {}) { throw TelegramApiException(500, "Receive invalid response") }
        }
        assertEquals("UNKNOWN", journal().status("work", "a", 42)["status"])

        assertFailsWith<TelegramApiException> {
            journal().sendCompletion("work", "b", 42, "fp", {}) { throw TelegramApiException(429, "Too Many Requests: retry after 1") }
        }
        assertEquals("UNKNOWN", journal().status("work", "b", 42)["status"], "an album may be partially delivered")
    }

    @Test
    fun `local rejections before TDLib are REJECTED, a sent timeout stays UNKNOWN`() {
        val reason = dev.telegrammcp.server.exception.TelegramTimeoutException.Reason.TOOL_DEADLINE
        assertFailsWith<dev.telegrammcp.server.exception.TelegramBusyException> {
            journal().send("work", "busy", 42, "fp", {}) {
                throw dev.telegrammcp.server.exception.TelegramBusyException("telegram-work", 25, retryable = true)
            }
        }
        assertEquals("REJECTED", journal().status("work", "busy", 42)["status"])

        assertFailsWith<dev.telegrammcp.server.exception.TelegramTimeoutException> {
            journal().send("work", "late", 42, "fp", {}) {
                throw dev.telegrammcp.server.exception.TelegramTimeoutException("SendMessage", reason, false, true, 0)
            }
        }
        assertEquals("REJECTED", journal().status("work", "late", 42)["status"])

        assertFailsWith<dev.telegrammcp.server.exception.SendOutcomeUnknownException> {
            journal().send("work", "sent", 42, "fp", {}) {
                throw dev.telegrammcp.server.exception.SendOutcomeUnknownException(
                    42, null, java.util.concurrent.TimeoutException(), deadlineExceeded = true,
                )
            }
        }
        assertEquals("UNKNOWN", journal().status("work", "sent", 42)["status"])
    }

    @Test
    fun `a local rejection after an earlier write of the call stays UNKNOWN`() {
        val reason = dev.telegrammcp.server.exception.TelegramTimeoutException.Reason.TOOL_DEADLINE
        assertFailsWith<dev.telegrammcp.server.exception.TelegramBusyException> {
            journal().send("work", "busy-after-write", 42, "fp", {}) {
                throw dev.telegrammcp.server.exception.TelegramBusyException("telegram-work", 25, retryable = false)
            }
        }
        assertEquals("UNKNOWN", journal().status("work", "busy-after-write", 42)["status"])

        assertFailsWith<dev.telegrammcp.server.exception.TelegramTimeoutException> {
            journal().send("work", "late-after-write", 42, "fp", {}) {
                throw dev.telegrammcp.server.exception.TelegramTimeoutException("SendMessage", reason, false, false, 0)
            }
        }
        assertEquals("UNKNOWN", journal().status("work", "late-after-write", 42)["status"])

        assertFailsWith<dev.telegrammcp.server.exception.TelegramTimeoutException> {
            journal().send("work", "sent-read", 42, "fp", {}) {
                throw dev.telegrammcp.server.exception.TelegramTimeoutException("SendMessage", reason, true, true, 0)
            }
        }
        assertEquals("UNKNOWN", journal().status("work", "sent-read", 42)["status"], "a sent request is never REJECTED")
    }

    @Test
    fun `a flood after an earlier write keeps the receipt UNKNOWN and cannot resend`() {
        val failures = listOf(
            TelegramApiException(429, "retry after 7").apply { priorWriteDispatched = true },
            TelegramFloodGateException("send chat 42", 7).apply { priorWriteDispatched = true },
        )
        failures.forEachIndexed { index, error ->
            val key = "after-write-$index"
            assertFailsWith<Exception> { journal().send("work", key, 42, "fp", {}) { throw error } }
            assertEquals("UNKNOWN", journal().status("work", key, 42)["status"])
            val replay = journal().send("work", key, 42, "fp", {}) { fail("must not resend an uncertain write") }
            assertEquals("UNKNOWN", replay["status"])
            assertEquals(true, replay["replayed"])
        }
    }
}
