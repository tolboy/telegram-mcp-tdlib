package dev.telegrammcp.server.security

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter
import java.util.Base64

/**
 * Optional SEP-2243 check for the 2026-07-28 routing headers.
 *
 * Clients on protocol 2025-06-18 and 2025-11-25 omit `Mcp-Method` and
 * `Mcp-Name`. Those requests stay allowed. When either header is present it
 * must match the JSON-RPC body, or the response is HTTP 400 with code -32020.
 * The headers are not required.
 */
internal object McpRoutingHeaderPolicy {
    const val METHOD_HEADER = "Mcp-Method"
    const val NAME_HEADER = "Mcp-Name"
    const val MAX_BODY_BYTES = 1_048_576
    const val ERROR_CODE = -32020

    fun evaluate(methodHeader: String?, nameHeader: String?, body: ByteArray, mapper: ObjectMapper): ByteArray? {
        val root = runCatching { mapper.readTree(body) }.getOrNull()
        if (root !is ObjectNode) {
            return error(mapper, null, "Header mismatch: MCP routing headers were present but the body is not a JSON object")
        }
        val id = root.get("id")
        if (methodHeader != null) {
            val method = root.get("method")?.takeIf { it.isTextual }?.asText()
            if (methodHeader != method) {
                return error(mapper, id, "Header mismatch: Mcp-Method does not match the JSON-RPC method")
            }
        }
        if (nameHeader != null) {
            val decoded = decodeHeaderValue(nameHeader)
                ?: return error(mapper, id, "Header mismatch: Mcp-Name is not a valid header value")
            val method = root.get("method")?.takeIf { it.isTextual }?.asText()
            val expected = expectedName(method, root.get("params"))
            if (expected == null || decoded != expected) {
                return error(mapper, id, "Header mismatch: Mcp-Name does not match the JSON-RPC params")
            }
        }
        return null
    }

    fun expectedName(method: String?, params: JsonNode?): String? {
        if (params == null || !params.isObject) return null
        val field = when (method) {
            "tools/call", "prompts/get" -> "name"
            "resources/read" -> "uri"
            else -> return null
        }
        return params.get(field)?.takeIf { it.isTextual }?.asText()
    }

    fun decodeHeaderValue(value: String): String? {
        if (value.startsWith("=?base64?") && value.endsWith("?=")) {
            val payload = value.removePrefix("=?base64?").removeSuffix("?=")
            return try {
                String(Base64.getDecoder().decode(payload), Charsets.UTF_8)
            } catch (_: IllegalArgumentException) {
                null
            }
        }
        return value
    }

    private fun error(mapper: ObjectMapper, id: JsonNode?, message: String): ByteArray {
        val body = mapper.createObjectNode()
        body.put("jsonrpc", "2.0")
        if (id == null || id.isNull) body.putNull("id") else body.set<JsonNode>("id", id.deepCopy())
        body.putObject("error").put("code", ERROR_CODE).put("message", message)
        return mapper.writeValueAsBytes(body)
    }
}

internal class McpRoutingHeaderFilter : OncePerRequestFilter() {
    private val mapper = ObjectMapper()

    override fun shouldNotFilter(request: HttpServletRequest): Boolean = !isMcpPost(request)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val methodHeader = request.getHeader(McpRoutingHeaderPolicy.METHOD_HEADER)
        val nameHeader = request.getHeader(McpRoutingHeaderPolicy.NAME_HEADER)
        if (methodHeader == null && nameHeader == null) {
            filterChain.doFilter(request, response)
            return
        }
        if (request.contentLengthLong > McpRoutingHeaderPolicy.MAX_BODY_BYTES) {
            reject(response, tooLarge(mapper))
            return
        }
        val body = request.inputStream.readNBytes(McpRoutingHeaderPolicy.MAX_BODY_BYTES + 1)
        if (body.size > McpRoutingHeaderPolicy.MAX_BODY_BYTES) {
            reject(response, tooLarge(mapper))
            return
        }
        val failure = McpRoutingHeaderPolicy.evaluate(methodHeader, nameHeader, body, mapper)
        if (failure != null) {
            reject(response, failure)
            return
        }
        filterChain.doFilter(CachedBodyRequest(request, body), response)
    }

    private fun reject(response: HttpServletResponse, body: ByteArray) {
        response.status = HttpServletResponse.SC_BAD_REQUEST
        response.contentType = "application/json"
        response.characterEncoding = "UTF-8"
        response.setContentLength(body.size)
        response.outputStream.write(body)
    }

    private fun tooLarge(mapper: ObjectMapper): ByteArray {
        val body = mapper.createObjectNode()
        body.put("jsonrpc", "2.0")
        body.putNull("id")
        body.putObject("error")
            .put("code", McpRoutingHeaderPolicy.ERROR_CODE)
            .put("message", "Header mismatch: request body is too large to compare with MCP routing headers")
        return mapper.writeValueAsBytes(body)
    }
}

internal fun isMcpPost(request: HttpServletRequest): Boolean {
    if (!request.method.equals("POST", ignoreCase = true)) return false
    val path = request.requestURI.removePrefix(request.contextPath.orEmpty())
    return path == "/mcp" || path.startsWith("/mcp/")
}
