package dev.telegrammcp.server.security

import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
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
    fun `accepts only direct loopback requests`() {
        val ipv4 = MockHttpServletRequest().also { it.remoteAddr = "127.0.0.1" }
        val ipv6 = MockHttpServletRequest().also { it.remoteAddr = "::1" }

        assertTrue(SecurityConfig.isDirectLoopbackRequest(ipv4))
        assertTrue(SecurityConfig.isDirectLoopbackRequest(ipv6))
    }

    @Test
    fun `rejects any forwarding header presence without a trusted proxy policy`() {
        listOf(
            "Forwarded" to "for=127.0.0.1",
            "X-Forwarded-For" to "",
            "X-Real-IP" to "127.0.0.1",
            "CF-Connecting-IP" to "127.0.0.1",
        ).forEach { (header, value) ->
            val request = MockHttpServletRequest().also {
                it.remoteAddr = "127.0.0.1"
                it.addHeader(header, value)
            }
            assertFalse(SecurityConfig.isDirectLoopbackRequest(request), "Accepted forwarding header $header")
        }

        val duplicateXff = MockHttpServletRequest().also {
            it.remoteAddr = "127.0.0.1"
            it.addHeader("X-Forwarded-For", "")
            it.addHeader("X-Forwarded-For", "203.0.113.50")
        }
        assertFalse(SecurityConfig.isDirectLoopbackRequest(duplicateXff))
    }
}
