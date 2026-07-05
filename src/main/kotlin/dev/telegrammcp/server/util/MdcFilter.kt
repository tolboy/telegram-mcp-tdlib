package dev.telegrammcp.server.util

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/**
 * Populates MDC with a per-request `traceId` (from `X-Trace-Id` header or
 * auto-generated UUID) and an optional `sessionId` (`X-Session-Id`).
 *
 * Runs before all other filters so that every log line in the request
 * includes correlation IDs.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class MdcFilter : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val traceId = request.getHeader("X-Trace-Id")
            ?: UUID.randomUUID().toString().replace("-", "").take(16)
        val sessionId = request.getHeader("X-Session-Id")

        try {
            MDC.put(StructuredLogger.KEY_TRACE_ID, traceId)
            sessionId?.let { MDC.put(StructuredLogger.KEY_SESSION_ID, it) }

            // Echo trace ID back so callers can correlate
            response.setHeader("X-Trace-Id", traceId)

            filterChain.doFilter(request, response)
        } finally {
            MDC.remove(StructuredLogger.KEY_TRACE_ID)
            MDC.remove(StructuredLogger.KEY_SESSION_ID)
            MDC.remove(StructuredLogger.KEY_TOOL_NAME)
        }
    }
}
