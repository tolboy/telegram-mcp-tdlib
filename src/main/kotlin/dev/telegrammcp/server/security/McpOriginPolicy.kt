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
        if (origin == null) return true
        val trimmed = origin.trim()
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return false
        if (!uri.rawPath.isNullOrEmpty() || uri.rawQuery != null || uri.rawFragment != null || uri.rawUserInfo != null) return false
        val host = McpHostPolicy.hostOnly(uri.rawAuthority ?: return false) ?: return false
        if (extraOrigins.any { it.equals(trimmed, ignoreCase = true) }) return true
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
        if (hostHeader == null) return true
        val host = hostOnly(hostHeader.trim()) ?: return false
        if (isLoopback(host)) return true
        return extraHosts.any { candidate ->
            val allowed = hostOnly(candidate.trim()) ?: return@any false
            allowed.equals(host, ignoreCase = true)
        }
    }

    internal fun hostOnly(value: String): String? {
        if (value.isEmpty()) return null
        val uri = runCatching { URI("http://$value") }.getOrNull() ?: return null
        if (uri.rawAuthority != value || uri.rawUserInfo != null ||
            !uri.rawPath.isNullOrEmpty() || uri.rawQuery != null || uri.rawFragment != null) return null
        val host = uri.host ?: return null
        val portSuffix = if (value.startsWith('[')) value.substringAfter(']') else value.removePrefix(host)
        if (portSuffix.isNotEmpty()) {
            if (!portSuffix.startsWith(':')) return null
            val port = portSuffix.substring(1)
            if (port.isEmpty() || port.any { it !in '0'..'9' } || port.toIntOrNull() !in 0..65535) return null
        }
        return host.removePrefix("[").removeSuffix("]")
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
        if (request.getHeaders("Origin").toList().size > 1 ||
            !McpOriginPolicy.allows(request.getHeader("Origin"), extraOrigins)) {
            warnOnce(rejectedOrigins, request.getHeader("Origin"), "Origin", "MCP_ALLOWED_ORIGINS")
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Origin is not allowed")
            return
        }
        if (request.getHeaders("Host").toList().size > 1 ||
            !McpHostPolicy.allows(request.getHeader("Host"), extraHosts)) {
            warnOnce(rejectedHosts, request.getHeader("Host"), "Host", "MCP_ALLOWED_HOSTS")
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Host is not allowed")
            return
        }
        filterChain.doFilter(request, response)
    }

    private val log = dev.telegrammcp.server.util.StructuredLogger.forClass<McpOriginFilter>()
    private val rejectedHosts: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val rejectedOrigins: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * A deployment that upgraded without listing its public name sees nothing
     * but 403s; name the rejected value once so the fix is obvious. Bounded so
     * a scanner cycling Host values cannot grow the set or flood the log.
     */
    private fun warnOnce(seen: MutableSet<String>, value: String?, header: String, setting: String) {
        val shown = value?.take(MAX_LOGGED_VALUE_LENGTH)?.replace(Regex("[\\p{Cntrl}]"), "?") ?: "(multiple)"
        if (seen.size >= MAX_WARNED_VALUES || !seen.add(shown)) return
        log.warn("Rejected /mcp request with {} '{}'; add it to {} if this deployment should accept it", header, shown, setting)
    }

    private companion object {
        private const val MAX_WARNED_VALUES = 32
        private const val MAX_LOGGED_VALUE_LENGTH = 120
    }
}
