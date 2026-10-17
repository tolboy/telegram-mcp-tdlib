package dev.telegrammcp.server.cli

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionMaintenanceTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `doctor reports configured labels and never exposes environment secrets`() {
        val maintenance = SessionMaintenance(
            tempDir,
            mapOf(
                "TELEGRAM_ACCOUNTS_WORK_API_ID" to "123",
                "TELEGRAM_ACCOUNTS_PERSONAL_API_HASH" to "do-not-print-this",
            ),
        )

        val report = maintenance.doctor()

        assertEquals(listOf("personal", "work"), report.accounts.map { it.label })
        assertTrue(report.accounts.all { !it.exists && it.lock == SessionLock.NOT_INITIALIZED })
        assertFalse(report.toString().contains("do-not-print-this"))
    }

    @Test
    fun `clear requires explicit confirmation and removes only the standard account directory`() {
        val directory = tempDir.resolve("tdlib").resolve("default")
        Files.createDirectories(directory)
        Files.writeString(directory.resolve("td.sqlite"), "local-state")
        val maintenance = SessionMaintenance(tempDir, emptyMap())

        assertThrows<IllegalArgumentException> { maintenance.clear("default", confirmed = false) }
        val cleared = maintenance.clear("default", confirmed = true)

        assertTrue(cleared.deleted)
        assertFalse(Files.exists(directory))
    }

    @Test
    fun `clear refuses a live TDLib lock and custom directory`() {
        val directory = tempDir.resolve("tdlib").resolve("default")
        Files.createDirectories(directory)
        val database = directory.resolve("td.sqlite")
        Files.writeString(database, "local-state")
        val maintenance = SessionMaintenance(tempDir, emptyMap())

        FileChannel.open(database, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                assertThrows<IllegalStateException> { maintenance.clear("default", confirmed = true) }
            }
        }

        val custom = tempDir.resolve("custom-session")
        Files.createDirectories(custom)
        assertThrows<IllegalArgumentException> {
            SessionMaintenance(tempDir, mapOf("TDLIB_DATA_DIR" to custom.toString())).clear("default", confirmed = true)
        }
    }

    private fun session(vararg files: Pair<String, Int>): Path {
        val directory = tempDir.resolve("tdlib").resolve("default")
        Files.createDirectories(directory.resolve("db_files"))
        files.forEach { (name, size) -> Files.write(directory.resolve(name), ByteArray(size)) }
        Files.writeString(directory.resolve("db_files").resolve("photo.jpg"), "x")
        return directory
    }

    private fun <T> holding(file: Path, action: () -> T): T =
        FileChannel.open(file, StandardOpenOption.WRITE).use { channel -> channel.lock().use { action() } }

    private fun doctorLock() = SessionMaintenance(tempDir, emptyMap()).doctor().accounts.single().lock

    @Test
    fun `issue 27 - doctor reports LOCKED when any candidate lock file is held`() {
        // A: both files, td.binlog held (the live-TDLib case)
        var dir = session("td.binlog" to 4096, "td.sqlite" to 4096)
        assertEquals(SessionLock.LOCKED, holding(dir.resolve("td.binlog")) { doctorLock() })
        // B: both files, td.sqlite held
        assertEquals(SessionLock.LOCKED, holding(dir.resolve("td.sqlite")) { doctorLock() })
        // D: both files, none held
        assertEquals(SessionLock.UNLOCKED, doctorLock())
        // zero-length td.sqlite next to a held td.binlog
        dir.toFile().deleteRecursively()
        dir = session("td.binlog" to 4096, "td.sqlite" to 0)
        assertEquals(SessionLock.LOCKED, holding(dir.resolve("td.binlog")) { doctorLock() })
        assertEquals(SessionLock.LOCKED, holding(dir.resolve("td.sqlite")) { doctorLock() })
        // C: td.binlog only, held
        dir.toFile().deleteRecursively()
        dir = session("td.binlog" to 4096)
        assertEquals(SessionLock.LOCKED, holding(dir.resolve("td.binlog")) { doctorLock() })
    }

    @Test
    fun `issue 27 - clear fails closed and deletes nothing while td binlog is held`() {
        val dir = session("td.binlog" to 4096, "td.sqlite" to 4096)
        val before = Files.walk(dir).use { it.map { p -> dir.relativize(p).toString() }.sorted().toList() }
        holding(dir.resolve("td.binlog")) {
            assertThrows<IllegalStateException> { SessionMaintenance(tempDir, emptyMap()).clear("default", confirmed = true) }
        }
        val after = Files.walk(dir).use { it.map { p -> dir.relativize(p).toString() }.sorted().toList() }
        assertEquals(before, after)
        val cleared = SessionMaintenance(tempDir, emptyMap()).clear("default", confirmed = true)
        assertTrue(cleared.deleted)
        assertFalse(Files.exists(dir))
        assertEquals(emptyList(), Files.list(tempDir.resolve("tdlib")).use { it.toList() })
    }
}
