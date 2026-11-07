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
import java.nio.file.attribute.AclEntryFlag
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.UserPrincipal
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalAccessChecksTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `a posix ancestor may be replaced only when shared write lacks the sticky bit`() {
        assertTrue(LocalAccessChecks.posixAncestorAllowsReplacement(0b000_000_010))
        assertTrue(LocalAccessChecks.posixAncestorAllowsReplacement(0b000_010_000))
        assertFalse(LocalAccessChecks.posixAncestorAllowsReplacement(0b001_000_000_010))
        assertFalse(LocalAccessChecks.posixAncestorAllowsReplacement(0b111_000_000))
    }

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

    // ── Windows ACL policy over a fake ACL view ─────────────────────────────
    // The real check runs only on Windows; these tests drive the same policy
    // with principals named the way a German or Russian Windows names them.

    private data class FakePrincipal(private val display: String) : UserPrincipal {
        override fun getName(): String = display
    }

    private val owner = FakePrincipal("DESKTOP\\Anatolii")
    private val sids = mapOf(
        "Jeder" to "S-1-1-0",
        "NT-AUTORITÄT\\Authentifizierte Benutzer" to "S-1-5-11",
        "VORDEFINIERT\\Benutzer" to "S-1-5-32-545",
        "NT AUTHORITY\\ИНТЕРАКТИВНЫЕ" to "S-1-5-4",
        "BUILTIN\\Гости" to "S-1-5-32-546",
        "CORP\\Domänen-Benutzer" to "S-1-5-21-1111-2222-3333-513",
        "NT-AUTORITÄT\\SYSTEM" to "S-1-5-18",
        "VORDEFINIERT\\Administratoren" to "S-1-5-32-544",
        "DESKTOP\\Anatolii" to "S-1-5-21-1111-2222-3333-1001",
        "DESKTOP\\Gast-Konto" to "S-1-5-21-1111-2222-3333-1002",
    )
    private val resolver = LocalAccessChecks.SidResolver { sids[it.name] }
    private val unresolved = LocalAccessChecks.SidResolver { null }
    private val fileWrite = setOf(AclEntryPermission.WRITE_DATA, AclEntryPermission.APPEND_DATA, AclEntryPermission.DELETE)

    private fun allow(name: String, vararg permissions: AclEntryPermission, inheritOnly: Boolean = false): AclEntry =
        AclEntry.newBuilder()
            .setType(AclEntryType.ALLOW)
            .setPrincipal(if (name == owner.name) owner else FakePrincipal(name))
            .setPermissions(*permissions)
            .apply { if (inheritOnly) setFlags(AclEntryFlag.INHERIT_ONLY, AclEntryFlag.FILE_INHERIT) }
            .build()

    private fun deny(name: String, vararg permissions: AclEntryPermission): AclEntry =
        AclEntry.newBuilder().setType(AclEntryType.DENY).setPrincipal(FakePrincipal(name)).setPermissions(*permissions).build()

    private fun check(vararg acl: AclEntry, sidResolver: LocalAccessChecks.SidResolver = resolver) =
        LocalAccessChecks.checkWindowsAcl(acl.toList(), owner, sidResolver, fileWrite, "TEST_SECRET", "file")

    @Test
    fun `localized broad groups are rejected by SID`() {
        listOf(
            "Jeder",
            "NT-AUTORITÄT\\Authentifizierte Benutzer",
            "VORDEFINIERT\\Benutzer",
            "NT AUTHORITY\\ИНТЕРАКТИВНЫЕ",
            "BUILTIN\\Гости",
            "CORP\\Domänen-Benutzer",
        ).forEach { group ->
            val error = assertThrows<IllegalArgumentException>(group) {
                check(allow(owner.name, AclEntryPermission.WRITE_DATA), allow(group, AclEntryPermission.APPEND_DATA))
            }
            assertContains(error.message.orEmpty(), group)
        }
    }

    @Test
    fun `owner SYSTEM and Administrators may write`() {
        check(
            allow(owner.name, *AclEntryPermission.entries.toTypedArray()),
            allow("NT-AUTORITÄT\\SYSTEM", *AclEntryPermission.entries.toTypedArray()),
            allow("VORDEFINIERT\\Administratoren", *AclEntryPermission.entries.toTypedArray()),
        )
    }

    @Test
    fun `broad read access, deny entries, and inherit-only entries do not count as writes`() {
        check(
            allow(owner.name, AclEntryPermission.WRITE_DATA),
            allow("VORDEFINIERT\\Benutzer", AclEntryPermission.READ_DATA, AclEntryPermission.READ_ATTRIBUTES),
            deny("Jeder", AclEntryPermission.WRITE_DATA),
            allow("NT-AUTORITÄT\\Authentifizierte Benutzer", AclEntryPermission.WRITE_DATA, inheritOnly = true),
        )
    }

    @Test
    fun `other specific accounts are reported but not rejected`() {
        val findings = LocalAccessChecks.classify(
            listOf(allow(owner.name, AclEntryPermission.WRITE_DATA), allow("DESKTOP\\Gast-Konto", AclEntryPermission.WRITE_DATA)),
            owner,
            resolver,
            fileWrite,
        )
        assertEquals(emptyList(), findings.broad)
        assertEquals(listOf("DESKTOP\\Gast-Konto (S-1-5-21-1111-2222-3333-1002)"), findings.other)
    }

    @Test
    fun `unresolvable principals fall back to English names and raw SID strings`() {
        assertThrows<IllegalArgumentException> { check(allow("BUILTIN\\Users", AclEntryPermission.WRITE_DATA), sidResolver = unresolved) }
        assertThrows<IllegalArgumentException> { check(allow("S-1-5-4", AclEntryPermission.WRITE_DATA), sidResolver = unresolved) }
        // A localized name without a SID cannot be classified; it is not guessed at.
        check(allow("VORDEFINIERT\\Benutzer", AclEntryPermission.WRITE_DATA), sidResolver = unresolved)
    }

    @Test
    fun `parent folder rights that allow replacing the file are rejected for broad groups`() {
        val replace = setOf(AclEntryPermission.DELETE_CHILD, AclEntryPermission.WRITE_ACL, AclEntryPermission.WRITE_OWNER, AclEntryPermission.DELETE)
        // ProgramData-style "create files" for Users cannot overwrite an existing file.
        LocalAccessChecks.checkWindowsAcl(
            listOf(allow("VORDEFINIERT\\Benutzer", AclEntryPermission.ADD_FILE, AclEntryPermission.ADD_SUBDIRECTORY)),
            owner, resolver, replace, "TEST_SECRET", "parent folder",
        )
        val error = assertThrows<IllegalArgumentException> {
            LocalAccessChecks.checkWindowsAcl(
                listOf(allow("Jeder", AclEntryPermission.DELETE_CHILD)),
                owner, resolver, replace, "TEST_SECRET", "parent folder",
            )
        }
        assertContains(error.message.orEmpty(), "parent folder")
    }

    @Test
    fun `broad SID classification covers well-known and domain-relative groups`() {
        listOf("S-1-1-0", "s-1-5-11", "S-1-5-32-545", "S-1-5-4", "S-1-5-32-546", "S-1-5-21-1-2-3-513", "S-1-5-21-1-2-3-515")
            .forEach { assertTrue(LocalAccessChecks.isBroadSid(it), it) }
        listOf("S-1-5-18", "S-1-5-32-544", "S-1-5-21-1-2-3-1001", "S-1-5-21-1-2-3-500")
            .forEach { assertFalse(LocalAccessChecks.isBroadSid(it), it) }
    }

    @Test
    fun `posix parent folders writable by others need the sticky bit`() {
        val dir = Files.createDirectory(tempDir.resolve("shared"))
        val file = dir.resolve("secret")
        Files.writeString(file, "value")
        val ownerOnly = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        if (runCatching { Files.setPosixFilePermissions(file, ownerOnly) }.isFailure) return
        Files.setPosixFilePermissions(dir, PosixFilePermission.entries.toSet())
        assertThrows<IllegalArgumentException> { LocalAccessChecks.rejectSharedWrite(file, "test secret") }

        // /tmp-style sticky directories are fine: others cannot rename our file.
        Files.setAttribute(dir, "unix:mode", "1777".toInt(8))
        LocalAccessChecks.rejectSharedWrite(file, "test secret")
    }

    @Test
    fun `posix group writable parent cannot replace an owner-only policy file`() {
        val dir = Files.createDirectory(tempDir.resolve("group-shared"))
        val file = Files.writeString(dir.resolve("permissions.json"), "{}")
        val ownerOnly = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        if (runCatching { Files.setPosixFilePermissions(file, ownerOnly) }.isFailure) return
        Files.setAttribute(dir, "unix:mode", "0770".toInt(8))

        val failure = assertThrows<IllegalArgumentException> {
            LocalAccessChecks.rejectSharedWrite(file, "MCP_PERMISSIONS_FILE")
        }
        assertContains(failure.message.orEmpty(), "parent folder")

        // A sticky parent prevents a group member from replacing another
        // owner's file, just as it does for a world-writable /tmp directory.
        Files.setAttribute(dir, "unix:mode", "1770".toInt(8))
        LocalAccessChecks.rejectSharedWrite(file, "MCP_PERMISSIONS_FILE")
    }
}
