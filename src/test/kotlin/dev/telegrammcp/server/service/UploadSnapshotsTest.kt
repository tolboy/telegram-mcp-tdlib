package dev.telegrammcp.server.service

import dev.telegrammcp.server.exception.FileSecurityException
import dev.telegrammcp.server.exception.SendOutcomeUnknownException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UploadSnapshotsTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `a replaced source does not change the staged bytes and success deletes the copy`() {
        val source = directory.resolve("source.bin")
        Files.write(source, byteArrayOf(1, 2, 3))
        val root = directory.resolve("snapshots")
        val snapshots = UploadSnapshots(root, maxFileBytes = 100, maxTotalBytes = 100)
        lateinit var staged: Path
        snapshots.use(source) { copy ->
            staged = copy
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
        val retained = Files.list(root).use { it.filter { path -> Files.isRegularFile(path) }.toList() }
        assertEquals(1, retained.size)
        assertEquals(listOf<Byte>(1, 2, 3, 4), Files.readAllBytes(retained.single()).toList())
        assertFailsWith<FileSecurityException> { snapshots.use(source) { } }
        assertTrue(Files.exists(retained.single()))
    }
}
