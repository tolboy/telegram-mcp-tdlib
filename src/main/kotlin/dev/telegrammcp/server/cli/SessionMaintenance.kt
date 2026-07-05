package dev.telegrammcp.server.cli

import dev.telegrammcp.server.client.TelegramAccountRegistry
import dev.telegrammcp.server.service.PlatformPaths
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

/** Offline-safe session inspection and clearing. It never initializes TDLib. */
class SessionMaintenance(
    private val applicationDataDirectory: Path,
    private val environment: Map<String, String>,
) {
    fun doctor(): SessionDoctorReport {
        val accounts = configuredLabels().map { label ->
            val directory = databaseDirectory(label)
            SessionAccountReport(
                label = label,
                databaseDirectory = directory,
                exists = Files.isDirectory(directory),
                lock = inspectLock(directory),
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
        if (!Files.exists(directory)) return SessionClearReport(label, directory, deleted = false, message = "No TDLib session directory exists")
        require(!Files.isSymbolicLink(directory)) { "Refusing to clear a symbolic-link TDLib session directory" }

        when (inspectLock(directory)) {
            SessionLock.UNLOCKED, SessionLock.LOCK_FILE_MISSING -> Unit
            else -> throw IllegalStateException("Refusing to clear '$label': TDLib lock is not safely available")
        }
        deleteTree(directory, standardDirectory)
        return SessionClearReport(label, directory, deleted = true, message = "TDLib local session data cleared")
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

    private fun inspectLock(directory: Path): SessionLock {
        if (!Files.isDirectory(directory)) return SessionLock.NOT_INITIALIZED
        val lockFile = LOCK_FILE_NAMES.asSequence().map(directory::resolve)
            .firstOrNull { Files.isRegularFile(it) } ?: return SessionLock.LOCK_FILE_MISSING
        return try {
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
    }

    private fun deleteTree(directory: Path, expectedRoot: Path) {
        require(directory == expectedRoot && directory.startsWith(applicationDataDirectory.resolve("tdlib").normalize())) {
            "Refusing to delete a path outside the account TDLib storage"
        }
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

    companion object {
        private const val ACCOUNT_PREFIX = "TELEGRAM_ACCOUNTS_"
        private val ACCOUNT_SUFFIXES = listOf(
            "API_HASH_FILE", "BOT_TOKEN_FILE", "PASSWORD_FILE", "CODE_FILE", "DATABASE_DIRECTORY",
            "DOWNLOADS_DIRECTORY", "PROXY_PASSWORD_FILE", "PROXY_SECRET_FILE", "API_ID", "API_HASH",
            "PHONE_NUMBER", "BOT_TOKEN", "PASSWORD", "CODE", "LOG_VERBOSITY_LEVEL", "PROXY_TYPE",
            "PROXY_SERVER", "PROXY_PORT", "PROXY_USERNAME", "PROXY_PASSWORD", "PROXY_HTTP_ONLY", "PROXY_SECRET",
        )
        private val LOCK_FILE_NAMES = listOf("td.sqlite", "td.binlog")

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
