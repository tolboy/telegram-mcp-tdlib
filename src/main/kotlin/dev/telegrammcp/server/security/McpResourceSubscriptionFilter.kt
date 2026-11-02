package dev.telegrammcp.server.security

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.telegrammcp.server.resource.ResourceSubscriptionRegistry
import dev.telegrammcp.server.resource.TelegramResourceAccess
import dev.telegrammcp.server.resource.TelegramResourceUri
import io.modelcontextprotocol.spec.McpSchema
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Authorizes `resources/subscribe` and records who subscribed in which session.
 *
 * Runs after [McpSessionPrincipalFilter], so the session already belongs to the
 * caller. A subscription is accepted only for a resource the caller could read
 * now; [dev.telegrammcp.server.resource.ResourceUpdateNotifier] checks again
 * before each notification. Denials share one message so subscribing cannot
 * probe which chats exist.
 */
class McpResourceSubscriptionFilter(
    private val registry: ResourceSubscriptionRegistry,
    private val access: TelegramResourceAccess,
    private val mapper: ObjectMapper = ObjectMapper(),
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = request.requestURI.removePrefix(request.contextPath.orEmpty())
        return path != "/mcp" && !path.startsWith("/mcp/")
    }

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val session = request.getHeader(McpSessionPrincipalBinder.HEADER)
        val authentication = SecurityContextHolder.getContext().authentication
            ?.takeIf { it.isAuthenticated && it !is AnonymousAuthenticationToken }
        if (session == null || authentication == null) {
            filterChain.doFilter(request, response)
            return
        }
        registry.touch(session)
        if (!isMcpPost(request)) {
            filterChain.doFilter(request, response)
            forgetClosed(request, response, session)
            return
        }
        // A body this filter cannot read whole could hide an unauthorized subscribe, so it never
        // reaches the SDK. Content-Length is checked first; chunked bodies are bounded by the read.
        if (request.contentLengthLong > McpRoutingHeaderPolicy.MAX_BODY_BYTES) {
            reject(response, null, McpSchema.ErrorCodes.INVALID_REQUEST, TOO_LARGE, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE)
            return
        }
        val body = request.inputStream.readNBytes(McpRoutingHeaderPolicy.MAX_BODY_BYTES + 1)
        if (body.size > McpRoutingHeaderPolicy.MAX_BODY_BYTES) {
            reject(response, null, McpSchema.ErrorCodes.INVALID_REQUEST, TOO_LARGE, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE)
            return
        }
        val message = try { mapper.readTree(body) } catch (_: Exception) { null }
        val replay = CachedBodyRequest(request, body)
        if (message != null && message.isArray && message.any { method(it) in SUBSCRIPTION_METHODS }) {
            reject(response, null, McpSchema.ErrorCodes.INVALID_REQUEST, "Batched resource subscriptions are not supported")
            return
        }
        val uri = message?.path("params")?.path("uri")?.takeIf { it.isTextual }?.asText()
        when (message?.let(::method)) {
            McpSchema.METHOD_RESOURCES_SUBSCRIBE -> {
                val resource = TelegramResourceUri.parse(uri)
                if (uri == null || resource == null || resource.toString() != uri || !access.permits(resource)) {
                    reject(response, message.get("id"), McpSchema.ErrorCodes.RESOURCE_NOT_FOUND, "Resource not found")
                    return
                }
                if (!registry.canAdd(session, uri)) {
                    reject(response, message.get("id"), McpSchema.ErrorCodes.INVALID_PARAMS, "Resource subscription limit reached")
                    return
                }
                filterChain.doFilter(replay, response)
                if (response.status in 200..299) registry.add(session, authentication, uri)
            }
            McpSchema.METHOD_RESOURCES_UNSUBSCRIBE -> {
                filterChain.doFilter(replay, response)
                if (uri != null) registry.remove(session, uri)
            }
            else -> {
                filterChain.doFilter(replay, response)
                forgetClosed(request, response, session)
            }
        }
    }

    private fun forgetClosed(request: HttpServletRequest, response: HttpServletResponse, session: String) {
        if ((request.method.equals("DELETE", true) && response.status in 200..299) || response.status == 404) {
            registry.forget(session)
        }
    }

    private fun method(node: JsonNode): String? = node.path("method").takeIf { it.isTextual }?.asText()

    private fun reject(
        response: HttpServletResponse,
        id: JsonNode?,
        code: Int,
        message: String,
        status: Int = HttpServletResponse.SC_OK,
    ) {
        val body = mapper.createObjectNode()
        body.put("jsonrpc", "2.0")
        if (id == null || id.isNull || id.isMissingNode) body.putNull("id") else body.set<JsonNode>("id", id)
        body.putObject("error").put("code", code).put("message", message)
        val bytes = mapper.writeValueAsBytes(body)
        response.status = status
        response.contentType = "application/json"
        response.characterEncoding = "UTF-8"
        response.setContentLength(bytes.size)
        response.outputStream.write(bytes)
    }

    companion object {
        private const val TOO_LARGE = "Request body exceeds 1 MiB"
        private val SUBSCRIPTION_METHODS = setOf(McpSchema.METHOD_RESOURCES_SUBSCRIBE, McpSchema.METHOD_RESOURCES_UNSUBSCRIBE)
    }
}
