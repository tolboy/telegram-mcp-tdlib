package dev.telegrammcp.server.security

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalAccessChecksTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `shared windows principals include the multi-user builtin group and SID forms`() {
        assertTrue(LocalAccessChecks.isSharedPrincipal("Everyone"))
        assertTrue(LocalAccessChecks.isSharedPrincipal("NT AUTHORITY\\Authenticated Users"))
        assertTrue(LocalAccessChecks.isSharedPrincipal("BUILTIN\\Users"))
        assertTrue(LocalAccessChecks.isSharedPrincipal("S-1-5-32-545"))
        assertFalse(LocalAccessChecks.isSharedPrincipal("DESKTOP\\Anatolii"))
    }

    @Test
    fun `windows shared write grants are rejected while deny entries do not grant writes`() {
        val file = Files.createFile(tempDir.resolve("windows-secret"))
        val view = Files.getFileAttributeView(file, AclFileAttributeView::class.java) ?: return
        val users = file.fileSystem.userPrincipalLookupService.lookupPrincipalByGroupName("BUILTIN\\Users")
        val original = view.acl
        val entry = AclEntry.newBuilder().setPrincipal(users).setPermissions(AclEntryPermission.WRITE_DATA)
        try {
            view.acl = original + entry.setType(AclEntryType.DENY).build()
            LocalAccessChecks.rejectSharedWrite(file, "test secret")
            view.acl = original + entry.setType(AclEntryType.ALLOW).build()
            assertThrows<IllegalArgumentException> { LocalAccessChecks.rejectSharedWrite(file, "test secret") }
        } finally {
            view.acl = original
        }
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
