package dev.telegrammcp.server.service

import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlatformPathsTest {

    @Test
    fun `uses Windows local app data convention`() {
        val path = PlatformPaths.defaultApplicationDataDirectory(
            osName = "Windows 11",
            environment = mapOf("LOCALAPPDATA" to "C:/Users/test/AppData/Local"),
            userHome = Path.of("C:/Users/test"),
        )
        assertEquals(Path.of("C:/Users/test/AppData/Local/TelegramMcpServer").toAbsolutePath().normalize(), path)
    }

    @Test
    fun `uses macOS application support convention`() {
        val path = PlatformPaths.defaultApplicationDataDirectory(
            osName = "Mac OS X",
            environment = emptyMap(),
            userHome = Path.of("/Users/test"),
        )
        assertTrue(path.toString().replace('\\', '/').endsWith("/Users/test/Library/Application Support/TelegramMcpServer"))
    }

    @Test
    fun `uses XDG data convention on Linux`() {
        val path = PlatformPaths.defaultApplicationDataDirectory(
            osName = "Linux",
            environment = mapOf("XDG_DATA_HOME" to "/var/lib/test-data"),
            userHome = Path.of("/home/test"),
        )
        assertTrue(path.toString().replace('\\', '/').endsWith("/var/lib/test-data/telegram-mcp-server"))
    }
}
