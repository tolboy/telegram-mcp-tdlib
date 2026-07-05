package dev.telegrammcp.server.security

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class SecurityConfigTest {

    @Test
    fun `builds RFC 9728 metadata URI from canonical MCP resource`() {
        assertEquals(
            "https://mcp.example/.well-known/oauth-protected-resource",
            SecurityConfig.oauthMetadataUri("https://mcp.example/mcp"),
        )
    }

    @Test
    fun `accepts full loopback range`() {
        assertTrue(SecurityConfig.isLoopbackAddress("127.0.0.1"))
        assertTrue(SecurityConfig.isLoopbackAddress("127.42.7.9"))
        assertTrue(SecurityConfig.isLoopbackAddress("::1"))
    }

    @Test
    fun `rejects private networks because they are not the same host`() {
        assertFalse(SecurityConfig.isLoopbackAddress("10.123.45.67"))
        assertFalse(SecurityConfig.isLoopbackAddress("172.16.0.1"))
        assertFalse(SecurityConfig.isLoopbackAddress("172.31.255.254"))
        assertFalse(SecurityConfig.isLoopbackAddress("192.168.50.10"))
    }

    @Test
    fun `rejects non-local addresses`() {
        assertFalse(SecurityConfig.isLoopbackAddress("8.8.8.8"))
        assertFalse(SecurityConfig.isLoopbackAddress("fc00::1"))
    }

    @Test
    fun `accepts loopback reverse proxy only for a loopback client`() {
        assertTrue(SecurityConfig.isLoopbackRequest("127.0.0.1", "127.0.0.1"))
        assertTrue(SecurityConfig.isLoopbackRequest("::1", "[::1]"))
    }

    @Test
    fun `rejects untrusted or malformed forwarded chain`() {
        assertFalse(SecurityConfig.isLoopbackRequest("172.17.0.1", "127.0.0.1"))
        assertFalse(SecurityConfig.isLoopbackRequest("127.0.0.1", "192.168.1.99"))
        assertFalse(SecurityConfig.isLoopbackRequest("127.0.0.1", "127.0.0.1, not-an-ip"))
    }
}
