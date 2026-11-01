package dev.telegrammcp.server.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpServletResponseWrapper
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Duration

/** Ownership is assigned when the server issues an id, before it reaches the client. */
class McpSessionPrincipalBinder(
    private val maxSessions: Int = MAX_SESSIONS,
    private val idleTimeout: Duration = IDLE_TIMEOUT,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private data class Owner(val principal: String, var touched: Long)
    private val owners = HashMap<String, Owner>()
    private var reservations = 0

    init { require(maxSessions > 0 && !idleTimeout.isNegative && !idleTimeout.isZero) }

    @Synchronized fun reserve(): Boolean {
        expireIdleSessions()
        if (owners.size + reservations >= maxSessions) return false
        reservations++
        return true
    }

    @Synchronized fun releaseReservation() { reservations-- }

    @Synchronized fun bindIssuedSession(sessionId: String, principal: String) {
        check(sessionId.isNotBlank() && sessionId.length <= MAX_ID_LENGTH)
        check(principal.isNotBlank())
        val existing = owners[sessionId]
        check(existing == null || existing.principal == principal) { "MCP session is bound to another client" }
        owners[sessionId] = Owner(principal, nanoTime())
    }

    @Synchronized fun decide(sessionId: String, principal: String?): String? {
        if (sessionId.isBlank() || sessionId.length > MAX_ID_LENGTH) return "Invalid MCP session id"
        expireIdleSessions()
        val owner = owners[sessionId] ?: return "Unknown MCP session"
        if (owner.principal != principal) return "MCP session is bound to another client"
        owner.touched = nanoTime()
        return null
    }

    @Synchronized fun forget(sessionId: String, principal: String) {
        if (owners[sessionId]?.principal == principal) owners.remove(sessionId)
    }

    private fun expireIdleSessions() {
        val now = nanoTime()
        owners.entries.removeIf { now - it.value.touched >= idleTimeout.toNanos() }
    }

    companion object {
        private const val MAX_ID_LENGTH = 128
        const val MAX_SESSIONS = 4_096
        val IDLE_TIMEOUT: Duration = Duration.ofHours(24)
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

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val authentication = SecurityContextHolder.getContext().authentication
        val principal = authentication?.name?.takeIf {
            authentication.isAuthenticated && authentication !is AnonymousAuthenticationToken && it.isNotBlank()
        }
        if (principal == null) {
            response.status = HttpServletResponse.SC_UNAUTHORIZED
            return
        }
        val session = request.getHeader(McpSessionPrincipalBinder.HEADER)
        if (session != null) {
            val rejection = binder.decide(session, principal)
            if (rejection != null) {
                // sendError would dispatch to /error, whose deny-all policy
                // would mask the required unknown-session 404 with a 403.
                response.status = if (rejection == "Unknown MCP session") {
                    HttpServletResponse.SC_NOT_FOUND
                } else {
                    HttpServletResponse.SC_FORBIDDEN
                }
                return
            }
            filterChain.doFilter(request, response)
            // A rejected or failed DELETE must leave the live session protected.
            if ((request.method.equals("DELETE", true) && response.status in 200..299) || response.status == 404) {
                binder.forget(session, principal)
            }
            return
        }
        if (!binder.reserve()) {
            response.status = HttpServletResponse.SC_SERVICE_UNAVAILABLE
            return
        }
        try {
            filterChain.doFilter(request, object : HttpServletResponseWrapper(response) {
                override fun setHeader(name: String, value: String?) {
                    if (name.equals(McpSessionPrincipalBinder.HEADER, true) && value != null) {
                        binder.bindIssuedSession(value, principal)
                    }
                    super.setHeader(name, value)
                }
                override fun addHeader(name: String, value: String?) {
                    if (name.equals(McpSessionPrincipalBinder.HEADER, true) && value != null) {
                        binder.bindIssuedSession(value, principal)
                    }
                    super.addHeader(name, value)
                }
            })
        } finally { binder.releaseReservation() }
    }
}
