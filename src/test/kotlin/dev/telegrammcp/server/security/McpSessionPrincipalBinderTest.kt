package dev.telegrammcp.server.security

import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpSessionPrincipalBinderTest {
    @AfterEach fun clearIdentity() = SecurityContextHolder.clearContext()

    @Test fun `only server issued sessions are bound and unknown ids do not consume capacity`() {
        val binder = McpSessionPrincipalBinder(maxSessions = 1)
        repeat(5_000) { assertEquals("Unknown MCP session", binder.decide("unknown-$it", "a")) }
        assertTrue(binder.reserve())
        binder.bindIssuedSession("issued", "a")
        binder.releaseReservation()
        assertNull(binder.decide("issued", "a"))
        assertEquals("MCP session is bound to another client", binder.decide("issued", "b"))
        assertFalse(binder.reserve())
        binder.forget("issued", "b")
        assertFalse(binder.reserve())
        binder.forget("issued", "a")
        assertTrue(binder.reserve())
        binder.releaseReservation()
    }

    @Test fun `reservation cap is atomic and released on initialization failure`() {
        val binder = McpSessionPrincipalBinder(maxSessions = 1)
        assertTrue(binder.reserve())
        assertFalse(binder.reserve())
        binder.releaseReservation()
        assertTrue(binder.reserve())
        binder.releaseReservation()
    }

    @Test fun `idle sessions release capacity while activity renews the owner lease`() {
        var now = 0L
        val binder = McpSessionPrincipalBinder(maxSessions = 1, idleTimeout = Duration.ofNanos(100)) { now }
        assertTrue(binder.reserve())
        binder.bindIssuedSession("issued", "a")
        binder.releaseReservation()
        now = 90
        assertNull(binder.decide("issued", "a"))
        now = 150
        assertFalse(binder.reserve())
        now = 190
        assertTrue(binder.reserve())
        assertEquals("Unknown MCP session", binder.decide("issued", "b"))
        binder.releaseReservation()
    }

    @Test fun `foreign DELETE is rejected and failed owner DELETE preserves ownership`() {
        val filter = McpSessionPrincipalFilter()
        fun call(client: String, method: String, id: String? = null, downstreamStatus: Int = 200): Int {
            SecurityContextHolder.getContext().authentication = ApiKeyAuthToken(client)
            val request = MockHttpServletRequest(method, "/mcp")
            id?.let { request.addHeader(McpSessionPrincipalBinder.HEADER, it) }
            val response = MockHttpServletResponse()
            filter.doFilter(request, response) { _, downstream ->
                (downstream as HttpServletResponse).status = downstreamStatus
                if (id == null) downstream.setHeader(McpSessionPrincipalBinder.HEADER, "issued")
            }
            return response.status
        }
        assertEquals(200, call("a", "POST"))
        assertEquals(403, call("b", "DELETE", "issued"))
        assertEquals(200, call("a", "POST", "issued"))
        assertEquals(405, call("a", "DELETE", "issued", 405))
        assertEquals(403, call("b", "POST", "issued"))
        assertEquals(200, call("a", "DELETE", "issued"))
        assertEquals(404, call("b", "POST", "issued"))
    }
}
