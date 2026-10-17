package dev.telegrammcp.server.cli

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
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
        // Both files, none held
        assertEquals(SessionLock.UNLOCKED, doctorLock())
        // zero-length td.sqlite next to a held td.binlog
        dir.toFile().deleteRecursively()
        dir = session("td.binlog" to 4096, "td.sqlite" to 0)
        assertEquals(SessionLock.LOCKED, holding(dir.resolve("td.binlog")) { doctorLock() })
        assertEquals(SessionLock.LOCKED, holding(dir.resolve("td.sqlite")) { doctorLock() })
        // td.binlog only, held
        dir.toFile().deleteRecursively()
        dir = session("td.binlog" to 4096)
        assertEquals(SessionLock.LOCKED, holding(dir.resolve("td.binlog")) { doctorLock() })
    }

    @Test
    fun `issue 27 - clear fails closed and deletes nothing while td binlog is held`() {
        val dir = session("td.binlog" to 4096, "td.sqlite" to 4096)
        val before = Files.walk(dir).use { it.map { p -> dir.relativize(p).toString() }.sorted().toList() }
        holding(dir.resolve("td.binlog")) {
            val error = assertThrows<IllegalStateException> { SessionMaintenance(tempDir, emptyMap()).clear("default", confirmed = true) }
            assertTrue(error.message.orEmpty().contains("TDLib lock is not safely available"))
        }
        val after = Files.walk(dir).use { it.map { p -> dir.relativize(p).toString() }.sorted().toList() }
        assertEquals(before, after)
        val cleared = SessionMaintenance(tempDir, emptyMap()).clear("default", confirmed = true)
        assertTrue(cleared.deleted)
        assertFalse(Files.exists(dir))
        assertEquals(emptyList(), Files.list(tempDir.resolve("tdlib")).use { it.toList() })
    }

    @Test
    fun `doctor probes binlog first and stops on a held ownership lock`() {
        session("td.binlog" to 0, "td.sqlite" to 0)
        val probes = mutableListOf<String>()
        val fs = object : SessionMaintenanceFileSystem() {
            override fun probeLock(lockFile: Path): SessionLock {
                probes += lockFile.fileName.toString()
                return SessionLock.LOCKED
            }
        }
        assertEquals(SessionLock.LOCKED, SessionMaintenance(tempDir, emptyMap(), fs).doctor().accounts.single().lock)
        assertEquals(listOf("td.binlog"), probes)
    }

    @Test
    fun `unavailable candidate fails closed unless another candidate is locked`() {
        val dir = session("td.binlog" to 0, "td.sqlite" to 0)
        var sqliteLock = SessionLock.UNLOCKED
        val fs = object : SessionMaintenanceFileSystem() {
            override fun probeLock(lockFile: Path) =
                if (lockFile.fileName.toString() == "td.binlog") SessionLock.UNAVAILABLE else sqliteLock
        }
        val maintenance = SessionMaintenance(tempDir, emptyMap(), fs)
        assertEquals(SessionLock.UNAVAILABLE, maintenance.doctor().accounts.single().lock)
        assertThrows<IllegalStateException> { maintenance.clear("default", true) }
        assertTrue(Files.exists(dir.resolve("db_files/photo.jpg")))
        sqliteLock = SessionLock.LOCKED
        assertEquals(SessionLock.LOCKED, maintenance.doctor().accounts.single().lock)
    }

    private fun stagedLockFileSystem(state: SessionLock = SessionLock.LOCKED) =
        object : SessionMaintenanceFileSystem() {
            override fun probeLock(lockFile: Path): SessionLock =
                if (lockFile.parent.fileName.toString().contains(".clearing-")) state else super.probeLock(lockFile)
        }

    @Test
    fun `lock acquired during staging restores session without deleting data`() {
        for (state in listOf(SessionLock.LOCKED, SessionLock.UNAVAILABLE)) {
            val dir = session("td.binlog" to 16)
            val maintenance = SessionMaintenance(tempDir, emptyMap(), stagedLockFileSystem(state))
            val error = assertThrows<IllegalStateException> { maintenance.clear("default", true) }
            assertTrue(error.message.orEmpty().contains("restored '$dir'; nothing was deleted"))
            assertEquals(16L, Files.size(dir.resolve("td.binlog")))
            assertEquals("x", Files.readString(dir.resolve("db_files/photo.jpg")))
            assertTrue(maintenance.doctor().accounts.single().stagedDirectories.isEmpty())
        }
    }

    @Test
    fun `failed restore preserves recreated file or directory and reports recoverable staged data`() {
        for (recreateFile in listOf(true, false)) {
            val root = tempDir.resolve(if (recreateFile) "file-case" else "directory-case")
            val dir = Files.createDirectories(root.resolve("tdlib/default"))
            Files.writeString(dir.resolve("td.binlog"), "original-session")
            val fs = object : SessionMaintenanceFileSystem() {
                override fun probeLock(lockFile: Path) =
                    if (lockFile.parent == dir) SessionLock.UNLOCKED else SessionLock.LOCKED

                override fun move(source: Path, target: Path, atomic: Boolean) {
                    super.move(source, target, atomic)
                    if (atomic) {
                        if (recreateFile) Files.writeString(source, "new-owner") else Files.createDirectory(source)
                    }
                }
            }
            val maintenance = SessionMaintenance(root, emptyMap(), fs)
            val error = assertThrows<IllegalStateException> { maintenance.clear("default", true) }
            val staged = maintenance.doctor().accounts.single().stagedDirectories.single()
            assertTrue(error.message.orEmpty().contains(staged.toString()))
            assertTrue(error.message.orEmpty().contains("nothing was deleted"))
            assertTrue(error.message.orEmpty().contains("'$dir' was not restored"))
            assertTrue(error.cause is java.nio.file.FileAlreadyExistsException)
            assertEquals("original-session", Files.readString(staged.resolve("td.binlog")))
            if (recreateFile) assertEquals("new-owner", Files.readString(dir)) else assertTrue(Files.isDirectory(dir))
            assertThrows<IllegalStateException> { maintenance.clear("default", true) }
        }
    }

    @Test
    fun `restore IO failure reports staging path even when canonical directory is absent`() {
        val dir = session("td.binlog" to 16)
        val fs = object : SessionMaintenanceFileSystem() {
            override fun probeLock(lockFile: Path) =
                if (lockFile.parent == dir) SessionLock.UNLOCKED else SessionLock.LOCKED

            override fun move(source: Path, target: Path, atomic: Boolean) {
                if (!atomic) throw IOException("restore failure")
                super.move(source, target, atomic)
            }
        }
        val maintenance = SessionMaintenance(tempDir, emptyMap(), fs)
        val error = assertThrows<IllegalStateException> { maintenance.clear("default", true) }
        val report = maintenance.doctor().accounts.single()
        assertFalse(report.exists)
        assertTrue(error.message.orEmpty().contains(report.stagedDirectories.single().toString()))
        assertEquals(16L, Files.size(report.stagedDirectories.single().resolve("td.binlog")))
        assertThrows<IllegalStateException> { maintenance.clear("default", true) }
    }

    @Test
    fun `unsupported atomic move reports both paths and preserves session`() {
        val dir = session("td.binlog" to 16)
        var targetPath: Path? = null
        val fs = object : SessionMaintenanceFileSystem() {
            override fun move(source: Path, target: Path, atomic: Boolean) {
                targetPath = target
                throw AtomicMoveNotSupportedException(source.toString(), target.toString(), "test filesystem")
            }
        }
        val error = assertThrows<IllegalStateException> { SessionMaintenance(tempDir, emptyMap(), fs).clear("default", true) }
        assertTrue(error.message.orEmpty().contains(dir.toString()))
        assertTrue(error.message.orEmpty().contains(targetPath.toString()))
        assertTrue(error.message.orEmpty().contains("AtomicMoveNotSupportedException"))
        assertTrue(error.message.orEmpty().contains("nothing was deleted"))
        assertFalse(error.message.orEmpty().contains("in use"))
        assertEquals(16L, Files.size(dir.resolve("td.binlog")))
    }

    @Test
    fun `deletion IO or safety failure keeps staged location discoverable`() {
        for (failure in listOf(IOException("delete failed"), IllegalArgumentException("safety check failed"))) {
            session("td.binlog" to 16)
            val fs = object : SessionMaintenanceFileSystem() {
                override fun deleteTree(directory: Path) {
                    if (failure is IOException) Files.delete(directory.resolve("td.binlog"))
                    throw failure
                }
            }
            val maintenance = SessionMaintenance(tempDir, emptyMap(), fs)
            val error = assertThrows<IllegalStateException> { maintenance.clear("default", true) }
            val staged = maintenance.doctor().accounts.single().stagedDirectories.single()
            assertTrue(error.message.orEmpty().contains(staged.toString()))
            assertEquals(failure, error.cause)
            assertEquals("x", Files.readString(staged.resolve("db_files/photo.jpg")))
            assertEquals(failure !is IOException, Files.exists(staged.resolve("td.binlog")))
            Files.move(staged, tempDir.resolve("tdlib/default"))
        }
    }

    @Test
    fun `doctor groups leftover staging paths by configured account`() {
        val work = Files.createDirectories(tempDir.resolve("tdlib/.work.clearing-123"))
        Files.createDirectories(tempDir.resolve("tdlib/.personal.clearing-456"))
        Files.createDirectories(tempDir.resolve("tdlib/.work-other.clearing-789"))
        val account = SessionMaintenance(tempDir, mapOf("TELEGRAM_ACCOUNTS_WORK_API_ID" to "1"))
            .doctor().accounts.single()
        assertFalse(account.exists)
        assertEquals(listOf(work), account.stagedDirectories)
    }

    @Test
    fun `clear only removes staged session when a new canonical session appears`() {
        val dir = session("td.binlog" to 16)
        val fs = object : SessionMaintenanceFileSystem() {
            override fun move(source: Path, target: Path, atomic: Boolean) {
                super.move(source, target, atomic)
                if (atomic) {
                    Files.createDirectory(source)
                    Files.writeString(source.resolve("td.binlog"), "new-session")
                }
            }
        }
        val maintenance = SessionMaintenance(tempDir, emptyMap(), fs)
        assertTrue(maintenance.clear("default", true).deleted)
        assertEquals("new-session", Files.readString(dir.resolve("td.binlog")))
        assertTrue(maintenance.doctor().accounts.single().stagedDirectories.isEmpty())
    }

    @Test
    fun `another JVM holding either candidate is detected and clear preserves all data`() {
        val dir = session("td.binlog" to 0, "td.sqlite" to 0)
        val source = tempDir.resolve("LockHolder.java")
        Files.writeString(source, """
            import java.nio.channels.FileChannel;
            import java.nio.file.*;
            class LockHolder {
                public static void main(String[] args) throws Exception {
                    try (var channel = FileChannel.open(Path.of(args[0]), StandardOpenOption.WRITE);
                         var lock = channel.lock()) {
                        Files.writeString(Path.of(args[1]), "ready");
                        System.in.read();
                    }
                }
            }
        """.trimIndent())
        for (name in listOf("td.binlog", "td.sqlite")) {
            val ready = tempDir.resolve("$name.ready")
            val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
            withLocker(ProcessBuilder(java, source.toString(), dir.resolve(name).toString(), ready.toString()), ready) {
                assertEquals(SessionLock.LOCKED, doctorLock())
                val error = assertThrows<IllegalStateException> { SessionMaintenance(tempDir, emptyMap()).clear("default", true) }
                assertTrue(error.message.orEmpty().contains("TDLib lock is not safely available"))
                assertTrue(Files.exists(dir.resolve("td.binlog")))
                assertTrue(Files.exists(dir.resolve("td.sqlite")))
                assertEquals("x", Files.readString(dir.resolve("db_files/photo.jpg")))
            }
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `Windows exclusive sharing is unavailable and clear leaves session intact`() {
        val dir = session("td.binlog" to 16)
        val ready = tempDir.resolve("exclusive.ready")
        val script = """
            ${'$'}stream = [System.IO.File]::Open(${'$'}env:TEST_LOCK_FILE, 'Open', 'ReadWrite', 'None')
            try {
                [System.IO.File]::WriteAllText(${'$'}env:TEST_LOCK_READY, 'ready')
                [Console]::ReadLine() | Out-Null
            } finally { ${'$'}stream.Dispose() }
        """.trimIndent()
        val builder = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script)
        builder.environment()["TEST_LOCK_FILE"] = dir.resolve("td.binlog").toString()
        builder.environment()["TEST_LOCK_READY"] = ready.toString()
        withLocker(builder, ready) {
            assertEquals(SessionLock.UNAVAILABLE, doctorLock())
            assertThrows<IllegalStateException> { SessionMaintenance(tempDir, emptyMap()).clear("default", true) }
            assertEquals("x", Files.readString(dir.resolve("db_files/photo.jpg")))
        }
        assertEquals(16L, Files.size(dir.resolve("td.binlog")))
    }

    private fun withLocker(builder: ProcessBuilder, ready: Path, action: () -> Unit) {
        val log = ready.resolveSibling("${ready.fileName}.log")
        val process = builder.redirectErrorStream(true).redirectOutput(log.toFile()).start()
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            while (!Files.exists(ready) && process.isAlive && System.nanoTime() < deadline) {
                process.waitFor(50, TimeUnit.MILLISECONDS)
            }
            assertTrue(Files.exists(ready), "Locker did not become ready: ${Files.readString(log)}")
            action()
        } finally {
            process.outputStream.close()
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Locker did not exit")
            }
        }
    }
}
