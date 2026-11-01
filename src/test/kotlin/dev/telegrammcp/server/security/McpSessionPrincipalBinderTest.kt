package dev.telegrammcp.server.security

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class McpSessionPrincipalBinderTest {

    @Test
    fun `first principal owns the session and delete releases it`() {
        val binder = McpSessionPrincipalBinder()

        assertNull(binder.decide("sess-1", "client-a", delete = false))
        assertNull(binder.decide("sess-1", "client-a", delete = false))
        assertEquals(
            "MCP session is bound to another client",
            binder.decide("sess-1", "client-b", delete = false),
        )
        assertNull(binder.decide("sess-1", "client-a", delete = true))
        assertNull(binder.decide("sess-1", "client-b", delete = false))
    }

    @Test
    fun `missing session and an oversized id`() {
        val binder = McpSessionPrincipalBinder()

        assertNull(binder.decide(null, "client-a", delete = false))
        assertNull(binder.decide("   ", "client-a", delete = false))
        assertEquals(
            "MCP session id is too long",
            binder.decide("s".repeat(129), "client-a", delete = false),
        )
    }

    @Test
    fun `a new session past the cap is refused`() {
        val binder = McpSessionPrincipalBinder(maxSessions = 1)

        assertNull(binder.decide("one", "client-a", delete = false))
        assertEquals(
            "Too many MCP sessions",
            binder.decide("two", "client-a", delete = false),
        )
        assertNull(binder.decide("one", "client-a", delete = false))
    }
}
