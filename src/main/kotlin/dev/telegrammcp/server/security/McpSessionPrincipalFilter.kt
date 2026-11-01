package dev.telegrammcp.server.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter
import java.util.concurrent.ConcurrentHashMap

/**
 * Binds an MCP session id to the first authenticated principal that uses it.
 * A second client with its own valid key must not continue that session.
 * Requests without `Mcp-Session-Id` stay allowed: `initialize` has no id yet.
 */
class McpSessionPrincipalBinder(
    private val maxSessions: Int = 4_096,
) {
    private val owners = ConcurrentHashMap<String, String>()

    fun decide(sessionId: String?, principal: String?, delete: Boolean): String? {
        val session = sessionId?.trim().orEmpty()
        if (session.isEmpty()) return null
        if (session.length > MAX_ID_LENGTH) return "MCP session id is too long"
        if (delete) {
            owners.remove(session)
            return null
        }
        val owner = principal?.trim().orEmpty()
        if (owner.isEmpty()) return null
        val existing = owners.putIfAbsent(session, owner)
        if (existing == null) {
            if (owners.size > maxSessions) {
                owners.remove(session, owner)
                return "Too many MCP sessions"
            }
            return null
        }
        if (existing == owner) return null
        return "MCP session is bound to another client"
    }

    companion object {
        private const val MAX_ID_LENGTH = 128
        const val HEADER = "Mcp-Session-Id"
    }
}

class McpSessionPrincipalFilter(
    private val binder: McpSessionPrincipalBinder = McpSessionPrincipalBinder(),
) : OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = request.requestURI.removePrefix(request.contextPath.orEmpty())
        return path != "/mcp" && !path.startsWith("/mcp/")
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val authentication = SecurityContextHolder.getContext().authentication
        val principal = authentication?.name?.takeUnless {
            it.isBlank() || authentication is AnonymousAuthenticationToken
        }
        val rejection = binder.decide(
            sessionId = request.getHeader(McpSessionPrincipalBinder.HEADER),
            principal = principal,
            delete = request.method.equals("DELETE", ignoreCase = true),
        )
        if (rejection != null) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, rejection)
            return
        }
        filterChain.doFilter(request, response)
    }
}
