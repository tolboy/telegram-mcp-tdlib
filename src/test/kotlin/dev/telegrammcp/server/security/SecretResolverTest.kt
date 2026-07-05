package dev.telegrammcp.server.security

import dev.telegrammcp.server.service.PlatformPaths
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals

class SecretResolverTest {

    @TempDir
    lateinit var tempDir: Path

    private val resolver = SecretResolver(PlatformPaths())

    @Test
    fun `reads a terminal newline from mounted secret file`() {
        val file = tempDir.resolve("api-hash")
        Files.writeString(file, "secret-value\n")

        assertEquals("secret-value", resolver.resolve("", file.toString(), "test secret", required = true))
    }

    @Test
    fun `rejects simultaneous direct and file secrets`() {
        val file = tempDir.resolve("api-key")
        Files.writeString(file, "from-file")

        assertThrows<IllegalArgumentException> {
            resolver.resolve("from-environment", file.toString(), "test secret")
        }
    }

    @Test
    fun `rejects a symbolic-link secret file when supported`() {
        val target = tempDir.resolve("target")
        Files.writeString(target, "secret")
        val link = tempDir.resolve("link")
        val created = runCatching { Files.createSymbolicLink(link, target); true }.getOrDefault(false)
        if (!created) return

        assertThrows<IllegalArgumentException> {
            resolver.resolve("", link.toString(), "test secret")
        }
    }
}
