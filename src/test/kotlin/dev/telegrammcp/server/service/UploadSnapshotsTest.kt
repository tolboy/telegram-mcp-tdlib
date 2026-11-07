package dev.telegrammcp.server.service

import dev.telegrammcp.server.exception.FileSecurityException
import dev.telegrammcp.server.exception.SendOutcomeUnknownException
import dev.telegrammcp.server.exception.TelegramTimeoutException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UploadSnapshotsTest {
    @TempDir
    lateinit var directory: Path

    private fun payloads(root: Path): List<Path> = Files.walk(root).use { paths ->
        paths.filter { Files.isRegularFile(it) && it.fileName.toString() !in setOf(".store.lock", ".lease") }.toList()
    }

    @Test
    fun `a replaced source does not change the staged bytes and success deletes the copy`() {
        val source = directory.resolve("source.bin")
        Files.write(source, byteArrayOf(1, 2, 3))
        val root = directory.resolve("snapshots")
        val snapshots = UploadSnapshots(root, maxFileBytes = 100, maxTotalBytes = 100)
        lateinit var staged: Path
        snapshots.use(source) { copy ->
            staged = copy
            assertEquals(source.fileName, copy.fileName)
            Files.write(source, byteArrayOf(9))
            assertEquals(listOf<Byte>(1, 2, 3), Files.readAllBytes(copy).toList())
        }
        assertFalse(Files.exists(staged))
    }

    @Test
    fun `an unknown send outcome keeps the snapshot and a full budget rejects another copy`() {
        val source = directory.resolve("source.bin")
        Files.write(source, byteArrayOf(1, 2, 3, 4))
        val root = directory.resolve("snapshots")
        val snapshots = UploadSnapshots(root, maxFileBytes = 4, maxTotalBytes = 4)
        assertFailsWith<SendOutcomeUnknownException> {
            snapshots.use(source) { throw SendOutcomeUnknownException(7, cause = RuntimeException("lost")) }
        }
        val retained = payloads(root)
        assertEquals(1, retained.size)
        assertEquals(listOf<Byte>(1, 2, 3, 4), Files.readAllBytes(retained.single()).toList())
        assertFailsWith<FileSecurityException> { snapshots.use(source) { } }
        assertTrue(Files.exists(retained.single()))
    }

    @Test
    fun `timeouts and generic callback failures retain every possibly active album member`() {
        val source = Files.writeString(directory.resolve("source.ogg"), "content")
        val root = directory.resolve("snapshots")
        val snapshots = UploadSnapshots(root, 100, 100)
        val timeout = TelegramTimeoutException("SetChatPhoto", TelegramTimeoutException.Reason.LOCAL_CANCEL,
            requestSent = true, retryable = false, waitedMillis = 1)
        assertFailsWith<TelegramTimeoutException> { snapshots.use(source) { throw timeout } }
        assertEquals(1, payloads(root).size)
        assertFailsWith<IllegalStateException> { snapshots.useAll(listOf(source, source)) { error("partial callback failure") } }
        assertEquals(3, payloads(root).size)
    }

    @Test
    fun `expired retained copies are reclaimed on a later upload without requiring restart`() {
        val source = Files.writeString(directory.resolve("source.pdf"), "content")
        val root = directory.resolve("snapshots")
        var instant = Instant.parse("2026-11-07T00:00:00Z")
        val snapshots = UploadSnapshots(root, 100, 7, now = { instant })
        assertFailsWith<SendOutcomeUnknownException> { snapshots.use(source) { throw SendOutcomeUnknownException(7, cause = java.util.concurrent.TimeoutException()) } }
        val retained = payloads(root).single()
        instant = instant.plus(Duration.ofHours(25))
        snapshots.use(source) { assertFalse(Files.exists(retained)) }
        assertTrue(payloads(root).isEmpty())
    }

    @Test
    fun `another instance cannot expire an old snapshot that TDLib still uses`() {
        val source = Files.writeString(directory.resolve("source.pdf"), "content")
        val root = directory.resolve("snapshots")
        var instant = Instant.parse("2026-11-07T00:00:00Z")
        val first = UploadSnapshots(root, 100, 14, now = { instant })
        val second = UploadSnapshots(root, 100, 14, now = { instant })
        first.use(source) { active ->
            Files.setLastModifiedTime(active.parent.parent, FileTime.from(instant))
            instant = instant.plus(Duration.ofHours(25))
            second.use(source) { assertTrue(Files.exists(active), "Active per-snapshot lease must exclude expiry") }
            assertTrue(Files.exists(active))
        }
        assertTrue(payloads(root).isEmpty())
    }

    @Test
    fun `failed copy validation cleans partial bytes before another upload reserves the budget`() {
        val source = Files.writeString(directory.resolve("source.pdf"), "content")
        val root = directory.resolve("snapshots")
        val snapshots = UploadSnapshots(root, 100, 7)
        var checks = 0
        assertFailsWith<FileSecurityException> {
            snapshots.use(source, validateSource = {
                if (++checks == 3) throw FileSecurityException("source was replaced during copy")
            }) { error("Failed copy must not reach TDLib") }
        }
        assertTrue(payloads(root).isEmpty())
        snapshots.use(source) { assertEquals("content", Files.readString(it)) }
    }

    @Test
    fun `multiple cache instances share the byte cap and zero-byte files still have a count cap`() {
        val source = Files.writeString(directory.resolve("source.pdf"), "content")
        val root = directory.resolve("snapshots")
        val first = UploadSnapshots(root, 100, 7)
        val second = UploadSnapshots(root, 100, 7)
        first.use(source) {
            assertFailsWith<FileSecurityException> { second.use(source) { error("Shared byte budget was bypassed") } }
        }
        val empty = Files.createFile(directory.resolve("empty.dat"))
        val limited = UploadSnapshots(root, 100, 7, maxSnapshots = 1)
        assertFailsWith<SendOutcomeUnknownException> { limited.use(empty) { throw SendOutcomeUnknownException(7, cause = java.util.concurrent.TimeoutException()) } }
        assertFailsWith<FileSecurityException> { limited.use(empty) { } }
    }
}
