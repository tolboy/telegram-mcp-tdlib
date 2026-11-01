package dev.telegrammcp.server.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter
import java.net.URI

/**
 * Streamable HTTP requires a present, invalid `Origin` to be rejected with 403.
 * That stops a browser page from driving a local server after DNS rebinding.
 * Clients that are not browsers omit the header, and those requests stay allowed.
 */
object McpOriginPolicy {

    fun allows(origin: String?, extraOrigins: Set<String>): Boolean {
        if (origin.isNullOrBlank()) return true
        val trimmed = origin.trim()
        if (trimmed.equals("null", ignoreCase = true)) return false
        if (extraOrigins.any { it.equals(trimmed, ignoreCase = true) }) return true
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host?.trim()?.removePrefix("[")?.removeSuffix("]") ?: return false
        return host.equals("localhost", ignoreCase = true) || host == "127.0.0.1" || host == "::1"
    }
}

/**
 * A present `Host` must be loopback or an operator allow-list entry.
 * DNS rebinding can present the attacker's name while the socket is local.
 * Clients that omit Host stay allowed.
 */
object McpHostPolicy {

    fun allows(hostHeader: String?, extraHosts: Set<String>): Boolean {
        if (hostHeader.isNullOrBlank()) return true
        val host = hostOnly(hostHeader.trim()) ?: return false
        if (isLoopback(host)) return true
        return extraHosts.any { candidate ->
            val allowed = hostOnly(candidate.trim()) ?: return@any false
            allowed.equals(host, ignoreCase = true)
        }
    }

    internal fun hostOnly(value: String): String? {
        if (value.isEmpty()) return null
        if (value.startsWith("[")) {
            val end = value.indexOf(']')
            if (end <= 1) return null
            return value.substring(1, end)
        }
        val colon = value.lastIndexOf(':')
        if (colon > 0 && value.substring(colon + 1).all(Char::isDigit) && ':' !in value.substring(0, colon)) {
            val host = value.substring(0, colon)
            return host.ifEmpty { null }
        }
        return value
    }

    private fun isLoopback(host: String): Boolean =
        host.equals("localhost", ignoreCase = true) || host == "127.0.0.1" || host == "::1"
}

class McpOriginFilter(
    private val extraOrigins: Set<String>,
    private val extraHosts: Set<String> = emptySet(),
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
        if (!McpOriginPolicy.allows(request.getHeader("Origin"), extraOrigins)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Origin is not allowed")
            return
        }
        if (!McpHostPolicy.allows(request.getHeader("Host"), extraHosts)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Host is not allowed")
            return
        }
        filterChain.doFilter(request, response)
    }
}
