package dev.telegrammcp.server.security

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryFlag
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.UserPrincipal
import java.util.concurrent.ConcurrentHashMap

/**
 * Rejects secret and policy files that other local users can rewrite, and
 * tightens a session directory to the owner when the filesystem allows it.
 *
 * Windows principals are classified by SID, not by account name: Windows
 * reports localized names ("Jeder", "Пользователи"), so a name list only
 * worked on English installations.
 */
object LocalAccessChecks {

    private val log = LoggerFactory.getLogger(LocalAccessChecks::class.java)

    /** Resolves an ACL principal to its string SID, or null when it cannot. */
    fun interface SidResolver {
        fun sidOf(principal: UserPrincipal): String?
    }

    /** Result of classifying the ALLOW entries that grant a dangerous permission. */
    data class AclFindings(
        /** Broad groups (Everyone, Users, Authenticated Users, …) that may write. */
        val broad: List<String>,
        /** Specific principals other than the owner, SYSTEM, and Administrators. */
        val other: List<String>,
    )

    fun rejectSharedWrite(path: Path, settingName: String) {
        rejectSharedWrite(path, settingName, defaultSidResolver)
    }

    internal fun rejectSharedWrite(path: Path, settingName: String, sidResolver: SidResolver) {
        val posix = runCatching { Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS) }.getOrNull()
        if (posix != null) {
            val writableByOthers = setOf(
                PosixFilePermission.GROUP_WRITE,
                PosixFilePermission.OTHERS_WRITE,
            )
            require(posix.none { it in writableByOthers }) {
                "$settingName must not be writable by group or others"
            }
            rejectReplaceablePosixParent(path, settingName)
            return
        }
        val view = Files.getFileAttributeView(path, AclFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
            ?: return
        checkWindowsAcl(view.acl, view.owner, sidResolver, FILE_WRITE_PERMISSIONS, settingName, "file")
        val parent = path.toAbsolutePath().parent ?: return
        val parentView = Files.getFileAttributeView(parent, AclFileAttributeView::class.java) ?: return
        checkWindowsAcl(
            parentView.acl,
            parentView.owner,
            sidResolver,
            DIRECTORY_REPLACE_PERMISSIONS,
            settingName,
            "parent folder",
        )
    }

    /**
     * Fails when a broad group may write; logs specific non-owner principals.
     * Pure over its inputs so the Windows policy is unit-testable on any OS.
     */
    internal fun checkWindowsAcl(
        acl: List<AclEntry>,
        owner: UserPrincipal?,
        sidResolver: SidResolver,
        dangerous: Set<AclEntryPermission>,
        settingName: String,
        subject: String,
    ) {
        val findings = classify(acl, owner, sidResolver, dangerous)
        require(findings.broad.isEmpty()) {
            "$settingName $subject must not be writable by shared Windows groups " +
                "(Everyone, Authenticated Users, Users, or similar); found: ${findings.broad.joinToString()}. " +
                "Remove those entries (icacls) so only you, SYSTEM, and Administrators can change it"
        }
        if (findings.other.isNotEmpty()) {
            log.warn(
                "{} {} is writable by other Windows principals: {}; restrict it to your account when possible",
                settingName,
                subject,
                findings.other.joinToString(),
            )
        }
    }

    internal fun classify(
        acl: List<AclEntry>,
        owner: UserPrincipal?,
        sidResolver: SidResolver,
        dangerous: Set<AclEntryPermission>,
    ): AclFindings {
        val ownerSid = owner?.let { runCatching { sidResolver.sidOf(it) }.getOrNull() }?.uppercase()
        // Windows evaluates ACEs in order. For an individual access bit, its
        // first applicable ALLOW/DENY decides; a later DENY cannot revoke a bit
        // already allowed (including explicit ALLOW before inherited DENY).
        // Evaluate each SID separately: cross-group token membership is not
        // inferred, so this deliberately errs toward reporting an unsafe grant.
        data class Rights(
            val label: String,
            val sid: String?,
            val principal: UserPrincipal,
            val allowed: MutableSet<AclEntryPermission> = mutableSetOf(),
            val decided: MutableSet<AclEntryPermission> = mutableSetOf(),
        )
        val byPrincipal = linkedMapOf<String, Rights>()
        for (entry in acl) {
            if (AclEntryFlag.INHERIT_ONLY in entry.flags()) continue
            if (entry.type() != AclEntryType.ALLOW && entry.type() != AclEntryType.DENY) continue
            val relevant = entry.permissions().filter { it in dangerous }.toSet()
            if (relevant.isEmpty()) continue
            val principal = entry.principal() ?: continue
            val label = principal.name ?: "<unnamed>"
            val sid = runCatching { sidResolver.sidOf(principal) }.getOrNull()?.uppercase()
            val rights = byPrincipal.getOrPut(sid ?: label) { Rights(label, sid, principal) }
            if (entry.type() == AclEntryType.ALLOW) rights.allowed += relevant - rights.decided
            rights.decided += relevant
        }
        val broad = mutableListOf<String>()
        val other = mutableListOf<String>()
        for (rights in byPrincipal.values) {
            if (rights.allowed.isEmpty()) continue
            val sid = rights.sid
            val rendered = if (sid == null) rights.label else "${rights.label} ($sid)"
            when {
                sid == null -> if (isSharedPrincipal(rights.principal.name)) broad += rights.label
                isBroadSid(sid) -> broad += rendered
                rights.principal == owner || sid == ownerSid || sid in TRUSTED_SIDS -> Unit
                else -> other += rendered
            }
        }
        return AclFindings(broad.distinct(), other.distinct())
    }

    /**
     * A group- or world-writable parent without the sticky bit lets another
     * user rename the file away and drop in their own, even if the file itself
     * is owner-only. We cannot assume a writable group is a user-private group.
     */
    private fun rejectReplaceablePosixParent(path: Path, settingName: String) {
        val parent = path.toAbsolutePath().parent ?: return
        val mode = runCatching { Files.getAttribute(parent, "unix:mode") as Int }.getOrNull() ?: return
        val sharedWrite = mode and 0b000_010_010 != 0
        val sticky = mode and 0b001_000_000_000 != 0
        require(!sharedWrite || sticky) {
            "$settingName parent folder must not be writable by group or others (or must have the sticky bit set)"
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
        auditSessionAncestors(path)
        enforceWindowsSessionDirectory(path)
    }

    /**
     * Rejects dangerous replace grants to every broad principal, including
     * BUILTIN\Users. Normal READ/EXECUTE grants do not match this mask.
     * The ACL is inspected in its existing order and is not rewritten.
     */
    internal fun enforceWindowsSessionDirectory(path: Path) {
        if (Files.getFileAttributeView(path, java.nio.file.attribute.PosixFileAttributeView::class.java) != null) return
        val view = Files.getFileAttributeView(path, AclFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS) ?: return
        checkWindowsAcl(view.acl, view.owner, defaultSidResolver, DIRECTORY_REPLACE_PERMISSIONS,
            "Session directory $path", "directory")
    }

    /**
     * A protected directory is only as private as the parents that can rename it
     * away. POSIX ancestors that are group- or world-writable without the sticky
     * bit fail startup. Windows ancestor ACLs are audited and logged: a normal
     * profile grants broad read, and refusing those would lock out the operator.
     */
    fun auditSessionAncestors(path: Path) {
        var current = path.toAbsolutePath().normalize().parent
        while (current != null && current.nameCount > 0) {
            val mode = runCatching { Files.getAttribute(current, "unix:mode") as Int }.getOrNull()
            if (mode != null) {
                require(!posixAncestorAllowsReplacement(mode)) {
                    "Session directory ancestor $current is writable by group or others without the sticky bit"
                }
            } else {
                runCatching {
                    val view = Files.getFileAttributeView(
                        current, AclFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS,
                    ) ?: return@runCatching
                    val findings = classify(view.acl, view.owner, defaultSidResolver, DIRECTORY_REPLACE_PERMISSIONS)
                    if (findings.broad.isNotEmpty()) {
                        log.warn(
                            "Session directory ancestor {} can be replaced by shared Windows groups: {}",
                            current,
                            findings.broad.joinToString(),
                        )
                    }
                }
            }
            val parent = current.parent
            if (parent == null || parent == current) break
            current = parent
        }
    }

    /** True when this POSIX mode lets someone other than the owner replace entries and the sticky bit is absent. */
    internal fun posixAncestorAllowsReplacement(mode: Int): Boolean {
        val sharedWrite = mode and 0b000_010_010 != 0
        val sticky = mode and 0b001_000_000_000 != 0
        return sharedWrite && !sticky
    }

    /**
     * English-name fallback, used only when a principal's SID cannot be
     * resolved. Localized installations rely on [isBroadSid].
     */
    fun isSharedPrincipal(name: String?): Boolean {
        val simple = name.orEmpty().substringAfterLast('\\').substringAfterLast('/').trim()
        val lower = simple.lowercase()
        return lower in SHARED_PRINCIPAL_NAMES || (looksLikeSid(simple) && isBroadSid(simple))
    }

    /** Well-known and domain-relative SIDs that cover more than one ordinary account. */
    fun isBroadSid(sid: String): Boolean {
        val normalized = sid.uppercase()
        if (normalized in BROAD_SIDS) return true
        // Domain Users (513), Domain Guests (514), Domain Computers (515).
        return DOMAIN_SID.matchEntire(normalized)?.groupValues?.get(1) in BROAD_DOMAIN_RIDS
    }

    fun looksLikeSid(value: String): Boolean = SID_PATTERN.matches(value)

    private val SID_PATTERN = Regex("(?i)S-1-\\d+(-\\d+)+")
    private val DOMAIN_SID = Regex("S-1-5-21-\\d+-\\d+-\\d+-(\\d+)")
    private val BROAD_DOMAIN_RIDS = setOf("513", "514", "515")

    private val BROAD_SIDS = setOf(
        "S-1-1-0", // Everyone
        "S-1-2-0", // LOCAL
        "S-1-2-1", // CONSOLE LOGON
        "S-1-5-1", // DIALUP
        "S-1-5-2", // NETWORK
        "S-1-5-3", // BATCH
        "S-1-5-4", // INTERACTIVE
        "S-1-5-6", // SERVICE
        "S-1-5-7", // ANONYMOUS LOGON
        "S-1-5-11", // Authenticated Users
        "S-1-5-13", // TERMINAL SERVER USER
        "S-1-5-14", // REMOTE INTERACTIVE LOGON
        "S-1-5-15", // This Organization
        "S-1-5-113", // Local account
        "S-1-5-32-545", // BUILTIN\Users
        "S-1-5-32-546", // BUILTIN\Guests
        "S-1-5-32-547", // BUILTIN\Power Users
        "S-1-5-32-555", // BUILTIN\Remote Desktop Users
        "S-1-15-2-1", // ALL APPLICATION PACKAGES
        "S-1-15-2-2", // ALL RESTRICTED APPLICATION PACKAGES
    )

    private val TRUSTED_SIDS = setOf(
        "S-1-5-18", // SYSTEM
        "S-1-5-32-544", // BUILTIN\Administrators
        "S-1-3-4", // OWNER RIGHTS
        "S-1-5-80-956008885-3418522649-1831038044-1853292631-2271478464", // TrustedInstaller
    )

    private val SHARED_PRINCIPAL_NAMES = setOf(
        "everyone",
        "authenticated users",
        "unauthenticated users",
        "anonymous logon",
        "interactive",
        "users",
        "guests",
        "domain users",
        "all application packages",
    )

    private val FILE_WRITE_PERMISSIONS = setOf(
        AclEntryPermission.WRITE_DATA,
        AclEntryPermission.WRITE_ATTRIBUTES,
        AclEntryPermission.WRITE_NAMED_ATTRS,
        AclEntryPermission.APPEND_DATA,
        AclEntryPermission.DELETE,
        AclEntryPermission.DELETE_CHILD,
        AclEntryPermission.WRITE_ACL,
        AclEntryPermission.WRITE_OWNER,
    )

    /**
     * Folder rights that let another account replace the file: delete or
     * rename its entries, or take over the folder ACL. ADD_FILE alone cannot
     * overwrite an existing file, so ProgramData-style "create files" grants pass.
     */
    private val DIRECTORY_REPLACE_PERMISSIONS = setOf(
        AclEntryPermission.DELETE,
        AclEntryPermission.DELETE_CHILD,
        AclEntryPermission.WRITE_ACL,
        AclEntryPermission.WRITE_OWNER,
    )

    private val sidCache = ConcurrentHashMap<String, String>()

    /**
     * SID strings pass through. Other names are round-tripped through
     * `LookupAccountNameW` on Windows, which accepts the same localized names
     * that `UserPrincipal.getName()` returned.
     */
    private val defaultSidResolver = SidResolver { principal ->
        val name = principal.name ?: return@SidResolver null
        if (looksLikeSid(name)) return@SidResolver name.uppercase()
        if (!WindowsSids.available) return@SidResolver null
        sidCache[name] ?: WindowsSids.lookup(name)?.also { sidCache[name] = it }
    }
}
