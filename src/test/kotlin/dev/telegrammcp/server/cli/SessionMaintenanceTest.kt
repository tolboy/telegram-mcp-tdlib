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
}
