package dev.telegrammcp.server.security

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalAccessChecksTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `shared windows principals are recognized without treating builtin users as shared`() {
        assertTrue(LocalAccessChecks.isSharedPrincipal("Everyone"))
        assertTrue(LocalAccessChecks.isSharedPrincipal("NT AUTHORITY\\Authenticated Users"))
        assertFalse(LocalAccessChecks.isSharedPrincipal("BUILTIN\\Users"))
        assertFalse(LocalAccessChecks.isSharedPrincipal("DESKTOP\\Anatolii"))
    }

    @Test
    fun `posix group write is rejected when the filesystem supports it`() {
        val file = tempDir.resolve("secret")
        Files.writeString(file, "value")
        val updated = runCatching {
            Files.setPosixFilePermissions(
                file,
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.GROUP_WRITE,
                ),
            )
        }.isSuccess
        if (!updated) return

        assertThrows<IllegalArgumentException> {
            LocalAccessChecks.rejectSharedWrite(file, "test secret")
        }
    }
}
