package dev.telegrammcp.server.service

import org.springframework.stereotype.Component
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * Resolves the server's local state directory using the platform convention.
 *
 * Relative paths from configuration are deliberately resolved below this
 * directory instead of the process working directory. That makes desktop,
 * service-manager, and IDE launches behave identically.
 */
@Component
class PlatformPaths {

    private val userHome: Path = Path.of(System.getProperty("user.home"))

    val applicationDataDirectory: Path by lazy {
        System.getenv("TELEGRAM_MCP_DATA_DIR")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { Path.of(it).toAbsolutePath().normalize() }
            ?: defaultApplicationDataDirectory(
                osName = System.getProperty("os.name"),
                environment = System.getenv(),
                userHome = userHome,
            )
    }

    fun tdLibDatabaseDirectory(accountLabel: String): Path =
        applicationDataDirectory.resolve("tdlib").resolve(accountLabel).normalize()

    fun tdLibDownloadsDirectory(accountLabel: String): Path =
        tdLibDatabaseDirectory(accountLabel).resolve("downloads")

    /** Resolves an absolute path as-is and a relative path below application data. */
    fun resolveApplicationPath(rawPath: String, settingName: String): Path = try {
        val supplied = Path.of(rawPath.trim())
        if (supplied.isAbsolute) supplied.normalize() else applicationDataDirectory.resolve(supplied).normalize()
    } catch (e: InvalidPathException) {
        throw IllegalArgumentException("$settingName is not a valid local path", e)
    }

    companion object {
        fun defaultApplicationDataDirectory(
            osName: String,
            environment: Map<String, String>,
            userHome: Path,
        ): Path = when {
            osName.startsWith("Windows", ignoreCase = true) ->
                Path.of(environment["LOCALAPPDATA"].orEmpty().ifBlank { userHome.resolve("AppData").resolve("Local").toString() })
                    .resolve("TelegramMcpServer")

            osName.startsWith("Mac", ignoreCase = true) ->
                userHome.resolve("Library").resolve("Application Support").resolve("TelegramMcpServer")

            else -> {
                val xdgDataHome = environment["XDG_DATA_HOME"].orEmpty()
                val base = if (xdgDataHome.isBlank()) userHome.resolve(".local").resolve("share") else Path.of(xdgDataHome)
                base.resolve("telegram-mcp-server")
            }
        }.toAbsolutePath().normalize()
    }
}
