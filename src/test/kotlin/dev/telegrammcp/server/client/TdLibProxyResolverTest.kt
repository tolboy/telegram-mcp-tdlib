package dev.telegrammcp.server.client

import dev.telegrammcp.server.config.TdLibProperties
import dev.telegrammcp.server.security.SecretResolver
import dev.telegrammcp.server.service.PlatformPaths
import it.tdlight.jni.TdApi
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TdLibProxyResolverTest {

    @TempDir
    lateinit var tempDir: Path

    private val resolver = TdLibProxyResolver(SecretResolver(PlatformPaths()))

    @Test
    fun `leaves networking direct when no proxy properties are configured`() {
        assertNull(resolver.resolve(TdLibProperties.Proxy(), "work"))
    }

    @Test
    fun `builds authenticated SOCKS5 proxy`() {
        val proxy = resolver.resolve(
            TdLibProperties.Proxy(
                type = "socks5",
                server = "proxy.example.test",
                port = 1080,
                username = "mcp-user",
                password = "mcp-password",
            ),
            "work",
        )!!

        assertEquals("proxy.example.test", proxy.server)
        assertEquals(1080, proxy.port)
        val type = proxy.type as TdApi.ProxyTypeSocks5
        assertEquals("mcp-user", type.username)
        assertEquals("mcp-password", type.password)
    }

    @Test
    fun `reads HTTP CONNECT password from a mounted secret file`() {
        val passwordFile = tempDir.resolve("proxy-password")
        Files.writeString(passwordFile, "from-file\n")

        val proxy = resolver.resolve(
            TdLibProperties.Proxy(
                type = "http",
                server = "127.0.0.1",
                port = 3128,
                username = "mcp-user",
                passwordFile = passwordFile.toString(),
            ),
            "work",
        )!!

        val type = proxy.type as TdApi.ProxyTypeHttp
        assertEquals("mcp-user", type.username)
        assertEquals("from-file", type.password)
        assertEquals(false, type.httpOnly)
    }

    @Test
    fun `reads an MTProto secret from a mounted secret file`() {
        val secretFile = tempDir.resolve("mtproto-secret")
        Files.writeString(secretFile, "dd00112233445566778899aabbccddeeff\n")

        val proxy = resolver.resolve(
            TdLibProperties.Proxy(
                type = "mtproto",
                server = "proxy.example.test",
                port = 443,
                secretFile = secretFile.toString(),
            ),
            "personal",
        )!!

        assertEquals("dd00112233445566778899aabbccddeeff", (proxy.type as TdApi.ProxyTypeMtproto).secret)
    }

    @Test
    fun `rejects partial or invalid proxy configuration before TDLib starts`() {
        assertThrows<IllegalArgumentException> {
            resolver.resolve(TdLibProperties.Proxy(server = "proxy.example.test", port = 1080), "work")
        }
        assertThrows<IllegalArgumentException> {
            resolver.resolve(TdLibProperties.Proxy(type = "socks5", server = "proxy.example.test", port = 0), "work")
        }
        assertThrows<IllegalArgumentException> {
            resolver.resolve(TdLibProperties.Proxy(type = "mtproto", server = "proxy.example.test", port = 443), "work")
        }
    }
}
