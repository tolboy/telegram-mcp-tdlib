package dev.telegrammcp.server.security

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletOutputStream
import jakarta.servlet.WriteListener
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpServletResponseWrapper
import org.springframework.web.filter.OncePerRequestFilter
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter

/**
 * Hides tools the current client cannot call when a permission file is enabled.
 *
 * The catalog registered at startup stays global. This filter rewrites only an
 * HTTP `tools/list` result. Call-time checks still apply, including on STDIO,
 * where this servlet filter is not installed. A response that is not a tool
 * list is left unchanged.
 */
internal class McpToolsListFilter(
    private val permissions: AccessPermissionService,
) : OncePerRequestFilter() {
    private val mapper = ObjectMapper()

    override fun shouldNotFilter(request: HttpServletRequest): Boolean = !isMcpPost(request)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (!permissions.enabled) {
            filterChain.doFilter(request, response)
            return
        }
        val cached = request as? CachedBodyRequest
        val body: ByteArray
        val downstream: HttpServletRequest
        if (cached != null) {
            body = cached.cachedBody
            downstream = cached
        } else {
            when (val read = peek(request)) {
                Peek.Skip -> {
                    filterChain.doFilter(request, response)
                    return
                }
                is Peek.Partial -> {
                    filterChain.doFilter(PrefixedBodyRequest(request, read.prefix, read.remainder), response)
                    return
                }
                is Peek.Complete -> {
                    body = read.bytes
                    downstream = CachedBodyRequest(request, body)
                }
            }
        }
        if (jsonMethod(body) != "tools/list") {
            filterChain.doFilter(downstream, response)
            return
        }
        val capture = CapturingResponse(response)
        filterChain.doFilter(downstream, capture)
        capture.finish()
        if (response.isCommitted) return
        val raw = capture.payload()
        val rewritten = if (capture.status == HttpServletResponse.SC_OK) {
            McpToolsListRewriter.rewrite(raw, response.contentType, permissions::allowsListedTool)
        } else {
            raw
        }
        response.setContentLength(rewritten.size)
        response.outputStream.write(rewritten)
    }

    private fun peek(request: HttpServletRequest): Peek {
        val declared = request.contentLengthLong
        if (declared > INSPECT_LIMIT) return Peek.Skip
        val peeked = request.inputStream.readNBytes(INSPECT_LIMIT + 1)
        if (peeked.size <= INSPECT_LIMIT) return Peek.Complete(peeked)
        return Peek.Partial(peeked, request.inputStream)
    }

    private fun jsonMethod(body: ByteArray): String? {
        val root = runCatching { mapper.readTree(body) }.getOrNull() as? ObjectNode ?: return null
        val method = root.get("method") ?: return null
        return method.takeIf { it.isTextual }?.asText()
    }

    private sealed interface Peek {
        data object Skip : Peek
        class Complete(val bytes: ByteArray) : Peek
        class Partial(val prefix: ByteArray, val remainder: jakarta.servlet.ServletInputStream) : Peek
    }

    companion object {
        private const val INSPECT_LIMIT = 65_536
    }
}

internal object McpToolsListRewriter {
    private val mapper = ObjectMapper()

    fun rewrite(body: ByteArray, contentType: String?, allow: (String) -> Boolean): ByteArray {
        if (body.isEmpty()) return body
        val text = body.toString(Charsets.UTF_8)
        val eventStream = contentType?.contains("event-stream", ignoreCase = true) == true ||
            text.startsWith("data:") || text.startsWith("event:")
        val rewritten = if (eventStream) rewriteSse(text, allow) else rewriteJson(text, allow) ?: return body
        return rewritten.toByteArray(Charsets.UTF_8)
    }

    fun rewriteJson(text: String, allow: (String) -> Boolean): String? {
        val root = runCatching { mapper.readTree(text) }.getOrNull() as? ObjectNode ?: return null
        val result = root.get("result") as? ObjectNode ?: return null
        val tools = result.get("tools") ?: return null
        if (!tools.isArray) return null
        val kept = mapper.createArrayNode()
        tools.forEach { tool ->
            val name = tool.path("name").asText("")
            if (tool.isObject && allow(name)) kept.add(tool)
        }
        result.set<JsonNode>("tools", kept)
        return mapper.writeValueAsString(root)
    }

    private fun rewriteSse(text: String, allow: (String) -> Boolean): String {
        val newline = if (text.contains("\r\n")) "\r\n" else "\n"
        return text.split("$newline$newline").joinToString("$newline$newline") { event ->
            rewriteEvent(event, newline, allow)
        }
    }

    private fun rewriteEvent(event: String, newline: String, allow: (String) -> Boolean): String {
        val lines = event.split(newline)
        val dataLines = lines.filter { it.startsWith("data:") }
        if (dataLines.isEmpty()) return event
        val json = dataLines.joinToString("\n") { it.removePrefix("data:").trimStart() }
        val rewritten = rewriteJson(json, allow) ?: return event
        var replaced = false
        return lines.joinToString(newline) { line ->
            if (!replaced && line.startsWith("data:")) {
                replaced = true
                "data: $rewritten"
            } else if (line.startsWith("data:")) {
                ""
            } else {
                line
            }
        }
    }
}

private class CapturingResponse(response: HttpServletResponse) : HttpServletResponseWrapper(response) {
    private val buffer = ByteArrayOutputStream()
    private val stream = object : ServletOutputStream() {
        override fun write(b: Int) {
            buffer.write(b)
        }

        override fun isReady(): Boolean = true

        override fun setWriteListener(listener: WriteListener?) = Unit
    }
    private var writer: PrintWriter? = null

    override fun getOutputStream(): ServletOutputStream = stream

    override fun getWriter(): PrintWriter {
        if (writer == null) {
            writer = PrintWriter(OutputStreamWriter(buffer, Charsets.UTF_8))
        }
        return writer!!
    }

    override fun flushBuffer() {
        writer?.flush()
    }

    fun finish() {
        writer?.flush()
    }

    fun payload(): ByteArray = buffer.toByteArray()
}
