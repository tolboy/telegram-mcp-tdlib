package dev.telegrammcp.server.security

import dev.telegrammcp.server.service.PlatformPaths
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

/**
 * Resolves secret material from either an environment-backed property or a
 * Docker/Podman/Kubernetes-style mounted secret file.
 *
 * Secrets are never written by the server. A file source is checked for common
 * unsafe conditions (symbolic links, non-regular files, excessive size, and
 * writable group/world POSIX permissions) before its contents are used.
 */
@Component
class SecretResolver(
    private val platformPaths: PlatformPaths,
) {

    private val log = LoggerFactory.getLogger(SecretResolver::class.java)

    fun resolve(
        directValue: String,
        fileName: String,
        settingName: String,
        required: Boolean = false,
    ): String {
        val direct = directValue.trim()
        val file = fileName.trim()
        require(direct.isBlank() || file.isBlank()) {
            "$settingName and ${settingName}_FILE cannot both be configured"
        }

        val resolved = when {
            direct.isNotBlank() -> direct
            file.isNotBlank() -> readSecretFile(platformPaths.resolveApplicationPath(file, "${settingName}_FILE"), settingName)
            else -> ""
        }
        if (required && resolved.isBlank()) {
            throw IllegalArgumentException("$settingName must be configured")
        }
        return resolved
    }

    private fun readSecretFile(path: Path, settingName: String): String {
        require(!Files.isSymbolicLink(path)) { "$settingName secret file must not be a symbolic link" }
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { "$settingName secret file must be a regular file" }
        require(Files.isReadable(path)) { "$settingName secret file is not readable" }

        val size = Files.size(path)
        require(size in 1..MAX_SECRET_BYTES) { "$settingName secret file must be between 1 and $MAX_SECRET_BYTES bytes" }
        validatePosixPermissions(path, settingName)

        // A final line break is customary for Docker/Kubernetes secret mounts;
        // it is not part of the credential. Other whitespace is retained.
        return Files.readString(path, StandardCharsets.UTF_8).removeSuffix("\r\n").removeSuffix("\n")
    }

    private fun validatePosixPermissions(path: Path, settingName: String) {
        val permissions = runCatching { Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS) }.getOrNull()
            ?: return // Windows and some mounted volumes do not expose POSIX ACLs.
        val writableByOthers = setOf(
            PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.OTHERS_WRITE,
        )
        require(permissions.none { it in writableByOthers }) {
            "$settingName secret file must not be writable by group or others"
        }
        if (permissions.any { it == PosixFilePermission.GROUP_READ || it == PosixFilePermission.OTHERS_READ }) {
            log.warn("{} secret file is readable by group/others; use an owner-only file when your secret mount permits it", settingName)
        }
    }

    private companion object {
        const val MAX_SECRET_BYTES = 16 * 1024L
    }
}
