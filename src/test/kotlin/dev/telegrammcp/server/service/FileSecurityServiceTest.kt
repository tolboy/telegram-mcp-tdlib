package dev.telegrammcp.server.service

import dev.telegrammcp.server.config.ServerModeProperties
import dev.telegrammcp.server.config.TdLibProperties
import dev.telegrammcp.server.exception.FileSecurityException
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileSecurityServiceTest {

    @TempDir
    lateinit var tempDir: Path

    private fun serviceWithRoots(vararg roots: String): FileSecurityService {
        val props = ServerModeProperties(
            fileSecurity = ServerModeProperties.FileSecurityProps(
                allowedRoots = roots.toList(),
            ),
        )
        return FileSecurityService(props)
    }

    private fun serviceWithNoRoots(): FileSecurityService {
        val props = ServerModeProperties(
            fileSecurity = ServerModeProperties.FileSecurityProps(
                allowedRoots = emptyList(),
            ),
        )
        return FileSecurityService(props)
    }

    // ── Deny all when no roots ──────────────────────────────────────────────

    @Test
    fun `rejects all paths when no roots configured`() {
        val service = serviceWithNoRoots()
        val ex = assertThrows<FileSecurityException> {
            service.validatePath("/any/path/file.txt")
        }
        assertTrue(ex.message.contains("no allowed roots"))
    }

    // ── Forbidden characters ────────────────────────────────────────────────

    @Test
    fun `rejects path with asterisk`() {
        val service = serviceWithRoots(tempDir.toString())
        assertThrows<FileSecurityException> {
            service.validatePath("$tempDir/*.txt")
        }
    }

    @Test
    fun `rejects path with question mark`() {
        val service = serviceWithRoots(tempDir.toString())
        assertThrows<FileSecurityException> {
            service.validatePath("$tempDir/file?.txt")
        }
    }

    @Test
    fun `rejects path with tilde`() {
        val service = serviceWithRoots(tempDir.toString())
        assertThrows<FileSecurityException> {
            service.validatePath("~/file.txt")
        }
    }

    // ── Path traversal ──────────────────────────────────────────────────────

    @Test
    fun `rejects path traversal with double dots`() {
        val service = serviceWithRoots(tempDir.toString())
        assertThrows<FileSecurityException> {
            service.validatePath("$tempDir/../../../etc/passwd")
        }
    }

    @Test
    fun `rejects path traversal with backslashes`() {
        val service = serviceWithRoots(tempDir.toString())
        assertThrows<FileSecurityException> {
            service.validatePath("$tempDir\\..\\..\\etc\\passwd")
        }
    }

    // ── Allowed roots enforcement ───────────────────────────────────────────

    @Test
    fun `accepts path within allowed root`() {
        val service = serviceWithRoots(tempDir.toString())
        val file = tempDir.resolve("test.txt")

        val result = service.validatePath(file.toString())

        assertEquals(file.toAbsolutePath().normalize(), result)
    }

    @Test
    fun `rejects path outside allowed root`() {
        val service = serviceWithRoots(tempDir.resolve("subdir").toString())
        assertThrows<FileSecurityException> {
            service.validatePath(tempDir.resolve("outside.txt").toString())
        }
    }

    // ── Upload validation ───────────────────────────────────────────────────

    @Test
    fun `upload validation rejects non-existent file`() {
        val service = serviceWithRoots(tempDir.toString())
        assertThrows<FileSecurityException> {
            service.validateForUpload("$tempDir/nonexistent.txt")
        }
    }

    @Test
    fun `upload validation rejects oversized file`() {
        val service = serviceWithRoots(tempDir.toString())
        val file = tempDir.resolve("big.txt")
        // Create a file larger than 1 byte limit for testing
        Files.writeString(file, "hello world content here")

        assertThrows<FileSecurityException> {
            service.validateForUpload(file.toString(), emptySet(), 1)
        }
    }

    @Test
    fun `upload validation rejects wrong extension`() {
        val service = serviceWithRoots(tempDir.toString())
        val file = tempDir.resolve("file.exe")
        Files.writeString(file, "test")

        assertThrows<FileSecurityException> {
            service.validateForUpload(file.toString(), setOf(".txt", ".pdf"), Long.MAX_VALUE)
        }
    }

    @Test
    fun `upload validation accepts correct extension`() {
        val service = serviceWithRoots(tempDir.toString())
        val file = tempDir.resolve("note.ogg")
        Files.writeString(file, "test audio content")

        val result = service.validateForUpload(
            file.toString(),
            setOf(".ogg", ".opus"),
            Long.MAX_VALUE,
        )

        assertTrue(result.toString().endsWith("note.ogg"))
    }

    @Test
    fun `upload validation rejects directories`() {
        val service = serviceWithRoots(tempDir.toString())
        val directory = Files.createDirectory(tempDir.resolve("folder"))

        val ex = assertThrows<FileSecurityException> {
            service.validateForUpload(directory.toString())
        }

        assertTrue(ex.message.contains("regular file"))
    }

    @Test
    fun `a file inside the TDLib database directory is rejected even under an allowed root`() {
        val session = tempDir.resolve("tdlib-session")
        val secret = session.resolve("td.binlog")
        Files.createDirectories(session)
        Files.writeString(secret, "session")
        val upload = tempDir.resolve("uploads").resolve("note.txt")
        Files.createDirectories(upload.parent)
        Files.writeString(upload, "ok")
        val props = ServerModeProperties(
            fileSecurity = ServerModeProperties.FileSecurityProps(
                allowedRoots = listOf(tempDir.toString()),
            ),
        )
        val service = FileSecurityService(
            props,
            tdLibProperties = TdLibProperties(
                database = TdLibProperties.Database(directory = session.toString()),
            ),
        )

        val rejected = assertThrows<FileSecurityException> {
            service.validatePath(secret.toString())
        }
        assertTrue(rejected.message.contains("TDLib session"))
        assertEquals(upload.toRealPath(), service.validateForUpload(upload.toString()))
    }

    @Test
    fun `snapshot copies cannot be read through upload or download paths under a broad allowed root`() {
        val state = tempDir.resolve("state")
        val staged = state.resolve("upload-snapshots/pending/content/private.pdf")
        Files.createDirectories(staged.parent)
        Files.writeString(staged, "another call's private upload")
        val paths = mockk<PlatformPaths> {
            every { applicationDataDirectory } returns state
            every { resolveApplicationPath(any(), any()) } answers { Path.of(firstArg<String>()).toAbsolutePath().normalize() }
        }
        val service = FileSecurityService(ServerModeProperties(fileSecurity = ServerModeProperties.FileSecurityProps(
            allowedRoots = listOf(tempDir.toString()))), paths)
        assertThrows<FileSecurityException> { service.validateForUpload(staged.toString()) }
        assertThrows<FileSecurityException> { service.validateForDownload(staged.resolveSibling("new.pdf").toString()) }
    }

    @Test
    fun `POSIX hard links cannot alias an outside file into an allowed upload root`() {
        assumeTrue("unix" in tempDir.fileSystem.supportedFileAttributeViews(), "POSIX link-count policy is unavailable")
        val allowed = Files.createDirectory(tempDir.resolve("allowed"))
        val outside = Files.writeString(tempDir.resolve("outside.bin"), "protected test bytes")
        val alias = Files.createLink(allowed.resolve("looks-safe.pdf"), outside)
        assertThrows<FileSecurityException> { serviceWithRoots(allowed.toString()).validateForUpload(alias.toString()) }
    }

    // ── Download validation ─────────────────────────────────────────────────

    @Test
    fun `download validation creates parent directory`() {
        val service = serviceWithRoots(tempDir.toString())
        val targetDir = tempDir.resolve("downloads/subdir/file.txt")

        service.validateForDownload(targetDir.toString())

        assertTrue(Files.isDirectory(targetDir.parent))
    }

    @Test
    fun `download validation rejects symlink escapes outside allowed roots`() {
        val outsideDir = Files.createTempDirectory("telegram-mcp-outside-")
        val link = tempDir.resolve("escape")
        assumeTrue(createSymlinkIfSupported(link, outsideDir), "Symbolic links are not supported in this environment")

        val service = serviceWithRoots(tempDir.toString())

        assertThrows<FileSecurityException> {
            service.validateForDownload(link.resolve("secret.txt").toString())
        }
    }

    private fun createSymlinkIfSupported(link: Path, target: Path): Boolean {
        return try {
            Files.createSymbolicLink(link, target)
            true
        } catch (_: UnsupportedOperationException) {
            false
        } catch (_: FileSystemException) {
            false
        } catch (_: SecurityException) {
            false
        } catch (_: IOException) {
            false
        }
    }
}
