package dev.telegrammcp.server.security

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.telegrammcp.server.client.McpCallCancellation
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Accepts MCP `notifications/cancelled` for the caller's session.
 *
 * The Java SDK 2.1.0 has no handler for that notification. This filter wakes
 * the matching local wait and answers 202 without forwarding the frame, so the
 * SDK cannot turn it into a method-not-found error. Closing the HTTP connection
 * is not handled here and is not treated as cancellation.
 */
internal class McpCancellationFilter : OncePerRequestFilter() {
    private val mapper = ObjectMapper()

    override fun shouldNotFilter(request: HttpServletRequest): Boolean = !isMcpPost(request)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (request.contentLengthLong > McpRoutingHeaderPolicy.MAX_BODY_BYTES) {
            filterChain.doFilter(request, response)
            return
        }
        val cached = request as? CachedBodyRequest
        val body = cached?.cachedBody ?: request.inputStream.readNBytes(McpRoutingHeaderPolicy.MAX_BODY_BYTES + 1)
        if (body.size > McpRoutingHeaderPolicy.MAX_BODY_BYTES) {
            filterChain.doFilter(if (cached != null) request else CachedBodyRequest(request, body), response)
            return
        }
        val replay = cached ?: CachedBodyRequest(request, body)
        val message = runCatching { mapper.readTree(body) }.getOrNull()
        if (message != null && message.isObject && !message.has("id") && message.path("method").asText() == METHOD) {
            requestId(message.path("params").path("requestId"))?.let { id ->
                McpCallCancellation.cancel(key(request.getHeader(McpSessionPrincipalBinder.HEADER), id))
            }
            response.status = HttpServletResponse.SC_ACCEPTED
            response.setContentLength(0)
            return
        }
        val callId = message?.takeIf { it.isObject && it.path("method").asText() == "tools/call" }
            ?.let { requestId(it.path("id")) }
        if (callId == null) {
            filterChain.doFilter(replay, response)
            return
        }
        McpCallCancellation.bind(key(request.getHeader(McpSessionPrincipalBinder.HEADER), callId))
        try {
            filterChain.doFilter(replay, response)
        } finally {
            McpCallCancellation.unbind()
        }
    }

    internal companion object {
        const val METHOD = "notifications/cancelled"

        fun key(sessionId: String?, requestId: String): String =
            AccessPermissionService.clientIdentity() + "\u0000" + (sessionId ?: "http-no-session") + "\u0000" + requestId

        /** JSON-RPC ids are strings or integers. The two forms do not match each other. */
        fun requestId(node: JsonNode): String? = when {
            node.isTextual -> "s:" + node.asText()
            node.isIntegralNumber && node.canConvertToLong() -> "n:" + node.longValue()
            else -> null
        }
    }
}
