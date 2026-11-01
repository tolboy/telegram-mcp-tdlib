package dev.telegrammcp.server.security

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpOriginPolicyTest {

    @Test
    fun `missing origin is allowed and a foreign origin is not`() {
        assertTrue(McpOriginPolicy.allows(null, emptySet()))
        assertFalse(McpOriginPolicy.allows("  ", emptySet()))
        assertFalse(McpOriginPolicy.allows("https://attacker.example", emptySet()))
        assertFalse(McpOriginPolicy.allows("null", emptySet()))
    }

    @Test
    fun `loopback browser origins and an operator allow list are accepted`() {
        assertTrue(McpOriginPolicy.allows("http://127.0.0.1:6274", emptySet()))
        assertTrue(McpOriginPolicy.allows("http://localhost:6274", emptySet()))
        assertTrue(McpOriginPolicy.allows("http://[::1]:6274", emptySet()))
        assertTrue(McpOriginPolicy.allows("https://claude.ai", setOf("https://claude.ai")))
        assertFalse(McpOriginPolicy.allows("https://claude.ai.attacker.example", setOf("https://claude.ai")))
    }

    @Test
    fun `host is loopback or an explicit allow list`() {
        assertTrue(McpHostPolicy.allows(null, emptySet()))
        assertTrue(McpHostPolicy.allows("localhost:8080", emptySet()))
        assertTrue(McpHostPolicy.allows("127.0.0.1:8080", emptySet()))
        assertTrue(McpHostPolicy.allows("[::1]:8080", emptySet()))
        assertTrue(McpHostPolicy.allows("mcp.example:443", setOf("mcp.example")))
        assertFalse(McpHostPolicy.allows("attacker.example", emptySet()))
        assertFalse(McpHostPolicy.allows("attacker.example", setOf("mcp.example")))
    }

    @Test
    fun `present malformed origins and hosts are rejected even when they contain loopback`() {
        for (origin in listOf("http://localhost/path", "http://user@localhost", "http://localhost?x=1",
            "http://localhost#fragment", "http://localhost:", "http://localhost:99999", "null", "")) {
            assertFalse(McpOriginPolicy.allows(origin, setOf(origin)), origin)
        }
        for (host in listOf("[::1]attacker.example", "[::1]:bad", "[::1]:99999", "localhost:",
            "localhost:99999", "localhost/path", "user@localhost", "localhost?x=1", "localhost#x", "")) {
            assertFalse(McpHostPolicy.allows(host, setOf(host)), host)
        }
    }
}
