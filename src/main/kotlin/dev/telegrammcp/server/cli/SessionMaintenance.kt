package dev.telegrammcp.server.cli

import dev.telegrammcp.server.client.TelegramAccountRegistry
import dev.telegrammcp.server.service.PlatformPaths
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

/** Offline-safe session inspection and clearing. It never initializes TDLib. */
class SessionMaintenance internal constructor(
    private val applicationDataDirectory: Path,
    private val environment: Map<String, String>,
    private val fileSystem: SessionMaintenanceFileSystem,
) {
    constructor(applicationDataDirectory: Path, environment: Map<String, String>) :
        this(applicationDataDirectory, environment, SessionMaintenanceFileSystem())

    fun doctor(): SessionDoctorReport {
        val accounts = configuredLabels().map { label ->
            val directory = databaseDirectory(label)
            SessionAccountReport(
                label = label,
                databaseDirectory = directory,
                exists = Files.isDirectory(directory),
                lock = inspectLock(directory),
                stagedDirectories = stagedDirectories(label),
            )
        }
        return SessionDoctorReport(
            applicationDataDirectory = applicationDataDirectory,
            accounts = accounts,
            logoutInstruction = "For the interactive default account, call POST /auth/logout on the running local server. " +
                "Named accounts must be logged out by the process that owns their TDLib client; this offline command never starts TDLib.",
            clearInstruction = "Stop the server first, run 'session doctor', then use 'session clear --account <label> --yes' only for an unlocked default storage directory.",
        )
    }

    fun clear(accountLabel: String, confirmed: Boolean): SessionClearReport {
        require(confirmed) { "Refusing to clear a TDLib session without --yes" }
        val label = TelegramAccountRegistry.normalizeLabel(accountLabel)
        require(label in configuredLabels()) { "Account '$label' is not configured in the current environment" }

        val directory = databaseDirectory(label)
        val standardDirectory = applicationDataDirectory.resolve("tdlib").resolve(label).normalize()
        require(directory == standardDirectory) {
            "Refusing to clear custom TDLib directory '$directory'; remove it manually after a backup and a successful doctor check"
        }
        check(stagedDirectories(label).isEmpty()) {
            "Refusing to clear '$label': staged session data exists; run 'session doctor' and recover it before retrying"
        }
        if (!Files.exists(directory)) return SessionClearReport(label, directory, deleted = false, message = "No TDLib session directory exists")
        require(!Files.isSymbolicLink(directory)) { "Refusing to clear a symbolic-link TDLib session directory" }

        when (inspectLock(directory)) {
            SessionLock.UNLOCKED, SessionLock.LOCK_FILE_MISSING -> Unit
            else -> throw IllegalStateException("Refusing to clear '$label': TDLib lock is not safely available")
        }
        // Windows can reject a rename while files are open. POSIX permits it
        // even with advisory locks, so re-check the staged tree before deletion.
        val staged = standardDirectory.resolveSibling(".$label.clearing-${System.nanoTime()}")
        try {
            fileSystem.move(directory, staged, atomic = true)
        } catch (e: IOException) {
            throw IllegalStateException("Refusing to clear '$label': could not stage '$directory' at '$staged' (${e.javaClass.simpleName}); nothing was deleted", e)
        }
        if (inspectLock(staged) !in setOf(SessionLock.UNLOCKED, SessionLock.LOCK_FILE_MISSING)) {
            try {
                // ATOMIC_MOVE may replace a newly created destination file.
                // Restore without replacement, preserving any new canonical path.
                fileSystem.move(staged, directory, atomic = false)
            } catch (e: IOException) {
                throw IllegalStateException("Refusing to clear '$label': staged lock is not safely available; nothing was deleted, but '$directory' was not restored. Session data remains at '$staged' (${e.javaClass.simpleName}); stop the owner and recover it manually", e)
            }
            throw IllegalStateException("Refusing to clear '$label': staged lock is not safely available; restored '$directory'; nothing was deleted")
        }
        try {
            deleteTree(staged, standardDirectory)
        } catch (e: Exception) {
            throw IllegalStateException("TDLib session for '$label' was detached to '$staged' but could not be fully removed (${e.javaClass.simpleName}); inspect it manually before retrying", e)
        }
        return SessionClearReport(label, directory, deleted = true, message = "TDLib local session data cleared")
    }

    private fun stagedDirectories(label: String): List<Path> {
        val root = applicationDataDirectory.resolve("tdlib").normalize()
        if (!Files.isDirectory(root)) return emptyList()
        return Files.list(root).use { entries ->
            entries.filter { it.fileName.toString().startsWith(".$label.clearing-") }
                .sorted().toList()
        }
    }

    private fun configuredLabels(): List<String> {
        val labels = environment.keys.asSequence()
            .filter { it.startsWith(ACCOUNT_PREFIX) }
            .mapNotNull(::labelFromAccountEnvironmentKey)
            .mapNotNull { raw -> runCatching { TelegramAccountRegistry.normalizeLabel(raw) }.getOrNull() }
            .distinct()
            .sorted()
            .toList()
        return labels.ifEmpty { listOf("default") }
    }

    private fun labelFromAccountEnvironmentKey(key: String): String? {
        val remainder = key.removePrefix(ACCOUNT_PREFIX)
        val suffix = ACCOUNT_SUFFIXES.firstOrNull { remainder.endsWith("_$it") } ?: return null
        return remainder.removeSuffix("_$suffix").takeIf { it.isNotBlank() }
    }

    private fun databaseDirectory(label: String): Path {
        val configured = if (label == "default") {
            environment["TDLIB_DATA_DIR"]
        } else {
            environment["${ACCOUNT_PREFIX}${label.uppercase()}_DATABASE_DIRECTORY"]
        }.orEmpty().trim()
        return if (configured.isBlank()) {
            applicationDataDirectory.resolve("tdlib").resolve(label).normalize()
        } else {
            val path = Path.of(configured)
            (if (path.isAbsolute) path else applicationDataDirectory.resolve(path)).normalize()
        }
    }

    /**
     * Probe TDLib's ownership lock (td.binlog) first, stopping on a held lock.
     * Fall back to other candidates even if an earlier probe is unavailable.
     * Empty files are probed too, using the standard whole-file tryLock range.
     */
    private fun inspectLock(directory: Path): SessionLock {
        if (!Files.isDirectory(directory)) return SessionLock.NOT_INITIALIZED
        val lockFiles = LOCK_FILE_NAMES.map(directory::resolve).filter { Files.isRegularFile(it) }
        if (lockFiles.isEmpty()) return SessionLock.LOCK_FILE_MISSING
        var result = SessionLock.UNLOCKED
        for (lockFile in lockFiles) {
            when (fileSystem.probeLock(lockFile)) {
                SessionLock.LOCKED -> return SessionLock.LOCKED
                SessionLock.UNAVAILABLE -> result = SessionLock.UNAVAILABLE
                else -> Unit
            }
        }
        return result
    }

    private fun deleteTree(directory: Path, expectedRoot: Path) {
        val tdlibRoot = applicationDataDirectory.resolve("tdlib").normalize()
        require(directory.normalize().parent == tdlibRoot && expectedRoot.parent == tdlibRoot &&
            directory.fileName.toString().startsWith(".${expectedRoot.fileName}.clearing-")) {
            "Refusing to delete a path outside the account TDLib storage"
        }
        fileSystem.deleteTree(directory)
    }

    companion object {
        private const val ACCOUNT_PREFIX = "TELEGRAM_ACCOUNTS_"
        private val ACCOUNT_SUFFIXES = listOf(
            "API_HASH_FILE", "BOT_TOKEN_FILE", "PASSWORD_FILE", "CODE_FILE", "DATABASE_DIRECTORY",
            "DOWNLOADS_DIRECTORY", "PROXY_PASSWORD_FILE", "PROXY_SECRET_FILE", "API_ID", "API_HASH",
            "PHONE_NUMBER", "BOT_TOKEN", "PASSWORD", "CODE", "LOG_VERBOSITY_LEVEL", "PROXY_TYPE",
            "PROXY_SERVER", "PROXY_PORT", "PROXY_USERNAME", "PROXY_PASSWORD", "PROXY_HTTP_ONLY", "PROXY_SECRET",
        )
        private val LOCK_FILE_NAMES = listOf("td.binlog", "td.sqlite")

        fun fromEnvironment(): SessionMaintenance = SessionMaintenance(
            PlatformPaths().applicationDataDirectory,
            System.getenv(),
        )
    }
}

data class SessionDoctorReport(
    val applicationDataDirectory: Path,
    val accounts: List<SessionAccountReport>,
    val logoutInstruction: String,
    val clearInstruction: String,
)

data class SessionAccountReport(
    val label: String,
    val databaseDirectory: Path,
    val exists: Boolean,
    val lock: SessionLock,
    val stagedDirectories: List<Path> = emptyList(),
) {
    /** Operating systems do not expose lock owner PIDs portably. */
    val lockOwner: String = "not available portably"
}

enum class SessionLock { NOT_INITIALIZED, LOCK_FILE_MISSING, UNLOCKED, LOCKED, UNAVAILABLE }

data class SessionClearReport(
    val label: String,
    val databaseDirectory: Path,
    val deleted: Boolean,
    val message: String,
)

/** Narrow filesystem seam for deterministic staging and recovery failure tests. */
internal open class SessionMaintenanceFileSystem {
    open fun move(source: Path, target: Path, atomic: Boolean) {
        if (atomic) {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
        } else {
            if (Files.exists(target, NOFOLLOW_LINKS)) throw java.nio.file.FileAlreadyExistsException(target.toString())
            Files.move(source, target)
        }
    }

    open fun probeLock(lockFile: Path): SessionLock = try {
        FileChannel.open(lockFile, StandardOpenOption.WRITE).use { channel ->
            try {
                channel.tryLock()?.use { SessionLock.UNLOCKED } ?: SessionLock.LOCKED
            } catch (_: OverlappingFileLockException) {
                SessionLock.LOCKED
            }
        }
    } catch (_: IOException) {
        SessionLock.UNAVAILABLE
    }

    open fun deleteTree(directory: Path) {
        Files.walkFileTree(directory, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exception: IOException?): FileVisitResult {
                if (exception != null) throw exception
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }
}
