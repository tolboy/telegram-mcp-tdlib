package dev.telegrammcp.server.service

import dev.telegrammcp.server.config.ServerModeProperties
import dev.telegrammcp.server.exception.FileSecurityException
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
