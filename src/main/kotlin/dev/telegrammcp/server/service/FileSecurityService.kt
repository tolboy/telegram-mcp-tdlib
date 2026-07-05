package dev.telegrammcp.server.service

import dev.telegrammcp.server.config.ServerModeProperties
import dev.telegrammcp.server.exception.FileSecurityException
import dev.telegrammcp.server.util.StructuredLogger
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * Validates file paths before any file I/O operation.
 *
 * Security checks (modeled after the Python telegram-mcp reference):
 * 1. **Forbidden characters** — glob wildcards, null bytes, bracket/brace/tilde chars
 * 2. **Path traversal** — `..` in any path segment is rejected
 * 3. **Canonical resolution** — real path must stay within allowed roots
 * 4. **Extension allowlist** — per-operation extension filtering
 * 5. **File size** — maximum configurable size limit
 *
 * When `allowedRoots` is empty, **all file operations are denied** (fail-closed).
 */
@Service
class FileSecurityService(
    private val props: ServerModeProperties,
    private val platformPaths: PlatformPaths = PlatformPaths(),
) {

    private val log = StructuredLogger.forClass<FileSecurityService>()

    private val allowedRoots: List<Path> =
        props.fileSecurity.allowedRoots
            .map { platformPaths.resolveApplicationPath(it, "server-mode.file-security.allowed-roots") }
            .map(::canonicalizeForComparison)
            .distinct()

    private val configExtensions: Set<String> =
        props.fileSecurity.allowedExtensions
            .asSequence()
            .map(::normalizeExtension)
            .toSet()

    companion object {
        /** Characters forbidden in file paths. */
        private val FORBIDDEN_CHARS = setOf('*', '?', '[', ']', '{', '}', '~', '\u0000')
    }

    /**
     * Validates a file path for general file operations (send/download).
     *
     * @param rawPath  the user-supplied path string
     * @return resolved, canonical [Path] guaranteed to be within allowed roots
     * @throws FileSecurityException if the path fails any check
     */
    fun validatePath(rawPath: String): Path {
        if (allowedRoots.isEmpty()) {
            throw FileSecurityException(
                "File operations are disabled: no allowed roots configured. " +
                    "Set server-mode.file-security.allowed-roots in configuration.",
            )
        }

        if (rawPath.isBlank()) {
            throw FileSecurityException("Path must not be blank")
        }

        validateCharacters(rawPath)
        validateNoTraversal(rawPath)

        val path = canonicalizeForComparison(resolvePath(rawPath))
        validateWithinRoots(path)

        return path
    }

    /**
     * Validates a path for upload with default settings from configuration.
     * Convenience overload for the most common use case.
     */
    fun validateForUpload(rawPath: String): Path {
        return validateForUpload(rawPath, emptySet(), props.fileSecurity.maxFileSizeBytes)
    }

    /**
     * Validates a path for upload operations with extension and size constraints.
     *
     * @param rawPath           user-supplied path
     * @param allowedExtensions specific extension set (empty = any extension)
     * @param maxSizeBytes      max allowed file size in bytes
     * @return validated canonical [Path]
     * @throws FileSecurityException if any check fails
     */
    fun validateForUpload(
        rawPath: String,
        allowedExtensions: Set<String>,
        maxSizeBytes: Long,
    ): Path {
        val path = validatePath(rawPath)

        // Extension check
        if (allowedExtensions.isNotEmpty()) {
            val dotExt = extensionOf(path)
            if (dotExt !in allowedExtensions) {
                throw FileSecurityException(
                    "File extension '$dotExt' is not allowed. Allowed: ${allowedExtensions.joinToString()}",
                )
            }
        }

        // Global extension allowlist from config
        if (configExtensions.isNotEmpty()) {
            val dotExt = extensionOf(path)
            if (dotExt !in configExtensions) {
                throw FileSecurityException(
                    "File extension '$dotExt' is not in the global allowlist",
                )
            }
        }

        // File must exist for upload
        if (!Files.exists(path)) {
            throw FileSecurityException("File does not exist: $path")
        }

        if (!Files.isRegularFile(path)) {
            throw FileSecurityException("Path is not a regular file: $path")
        }

        if (!Files.isReadable(path)) {
            throw FileSecurityException("File is not readable: $path")
        }

        // Size check
        val fileSize = Files.size(path)
        if (fileSize > maxSizeBytes) {
            val maxMb = maxSizeBytes / (1024 * 1024)
            throw FileSecurityException("File size (${fileSize / (1024 * 1024)}MB) exceeds maximum (${maxMb}MB)")
        }

        return path
    }

    /**
     * Validates a target directory for download operations.
     * Creates the directory if it doesn't exist but is within roots.
     *
     * @param rawPath target directory or full file path
     * @return validated canonical [Path] for the download destination
     * @throws FileSecurityException if the path fails any check
     */
    fun validateForDownload(rawPath: String): Path {
        if (allowedRoots.isEmpty()) {
            throw FileSecurityException(
                "File operations are disabled: no allowed roots configured.",
            )
        }

        if (rawPath.isBlank()) {
            throw FileSecurityException("Path must not be blank")
        }

        validateCharacters(rawPath)
        validateNoTraversal(rawPath)

        val path = canonicalizeForComparison(resolvePath(rawPath))
        validateWithinRoots(path)

        // Ensure parent directory exists (or create it)
        val parent = path.parent
        if (parent != null && !Files.exists(parent)) {
            try {
                Files.createDirectories(parent)
                log.debug("Created download directory: {}", parent)
            } catch (e: Exception) {
                throw FileSecurityException("Cannot create directory: ${e.message}")
            }
        }

        return path
    }

    // ── Internal validation steps ───────────────────────────────────────────

    private fun validateCharacters(rawPath: String) {
        for (ch in rawPath) {
            if (ch in FORBIDDEN_CHARS) {
                throw FileSecurityException("Path contains forbidden character: '$ch'")
            }
        }
    }

    private fun validateNoTraversal(rawPath: String) {
        // Split by both / and \ for cross-platform support
        val segments = rawPath.replace('\\', '/').split('/')
        if (segments.any { it == ".." }) {
            throw FileSecurityException("Path traversal (..) is not allowed")
        }
    }

    private fun resolvePath(rawPath: String): Path {
        return try {
            val path = Path.of(rawPath)
            if (path.isAbsolute) {
                path.toAbsolutePath().normalize()
            } else {
                // Resolve relative paths against the first allowed root
                allowedRoots.first().resolve(path).toAbsolutePath().normalize()
            }
        } catch (e: InvalidPathException) {
            throw FileSecurityException("Invalid path syntax: ${e.message}")
        }
    }

    private fun canonicalizeForComparison(path: Path): Path {
        val normalized = path.toAbsolutePath().normalize()
        if (Files.exists(normalized)) {
            return normalized.toRealPath()
        }

        val existingAncestor = generateSequence(normalized) { it.parent }
            .firstOrNull { Files.exists(it) }
            ?: normalized.root
            ?: normalized

        val realAncestor = if (Files.exists(existingAncestor)) {
            existingAncestor.toRealPath()
        } else {
            existingAncestor.toAbsolutePath().normalize()
        }

        return realAncestor.resolve(existingAncestor.relativize(normalized)).normalize()
    }

    private fun extensionOf(path: Path): String =
        normalizeExtension(path.fileName.toString().substringAfterLast('.', ""))

    private fun normalizeExtension(extension: String): String {
        val normalized = extension.trim().lowercase().removePrefix(".")
        return if (normalized.isBlank()) "." else ".$normalized"
    }

    private fun validateWithinRoots(canonicalPath: Path) {
        val isWithinRoots = allowedRoots.any { root ->
            canonicalPath.startsWith(root)
        }
        if (!isWithinRoots) {
            log.warn("Path '{}' is outside allowed roots: {}", canonicalPath, allowedRoots)
            throw FileSecurityException(
                "Path is outside allowed file system roots",
            )
        }
    }
}
