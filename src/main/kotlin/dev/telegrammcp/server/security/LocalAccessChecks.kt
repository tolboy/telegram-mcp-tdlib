package dev.telegrammcp.server.security

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermission

/**
 * Rejects secret and policy files that other local users can rewrite, and
 * tightens a session directory to the owner when the filesystem allows it.
 */
object LocalAccessChecks {

    fun rejectSharedWrite(path: Path, settingName: String) {
        val posix = runCatching { Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS) }.getOrNull()
        if (posix != null) {
            val writableByOthers = setOf(
                PosixFilePermission.GROUP_WRITE,
                PosixFilePermission.OTHERS_WRITE,
            )
            require(posix.none { it in writableByOthers }) {
                "$settingName must not be writable by group or others"
            }
            return
        }
        val view = Files.getFileAttributeView(path, AclFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
            ?: return
        val sharedWrite = view.acl.any { entry ->
            entry.type() == AclEntryType.ALLOW && isSharedPrincipal(entry.principal()?.name) &&
                entry.permissions().any { it in WRITE_PERMISSIONS }
        }
        require(!sharedWrite) {
            "$settingName must not be writable by shared Windows groups (Everyone, Authenticated Users, or Users)"
        }
    }

    /**
     * Creates [path] and, on POSIX, leaves it owner-only. Windows ACL rewrites
     * are not attempted: a mistaken ACE removal can lock the operator out of
     * the session directory. POSIX permission failures propagate instead of
     * silently leaving the directory shared.
     */
    fun prepareOwnerDirectory(path: Path) {
        Files.createDirectories(path)
        val ownerOnly = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
        )
        if (Files.getFileAttributeView(path, java.nio.file.attribute.PosixFileAttributeView::class.java) != null) {
            Files.setPosixFilePermissions(path, ownerOnly)
        }
    }

    fun isSharedPrincipal(name: String?): Boolean {
        val simple = name.orEmpty().substringAfterLast('\\').substringAfterLast('/').trim()
        return simple.lowercase() in SHARED_PRINCIPALS
    }

    private val SHARED_PRINCIPALS = setOf(
        "everyone",
        "authenticated users",
        "unauthenticated users",
        "users",
        "s-1-1-0",
        "s-1-5-11",
        "s-1-5-32-545",
    )

    private val WRITE_PERMISSIONS = setOf(
        AclEntryPermission.WRITE_DATA,
        AclEntryPermission.WRITE_ATTRIBUTES,
        AclEntryPermission.WRITE_NAMED_ATTRS,
        AclEntryPermission.APPEND_DATA,
        AclEntryPermission.DELETE,
        AclEntryPermission.DELETE_CHILD,
        AclEntryPermission.WRITE_ACL,
        AclEntryPermission.WRITE_OWNER,
    )
}
