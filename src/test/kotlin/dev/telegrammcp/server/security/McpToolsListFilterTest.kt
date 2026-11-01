package dev.telegrammcp.server.security

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.config.McpSecurityProperties
import dev.telegrammcp.server.service.PlatformPaths
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpToolsListFilterTest {
    @TempDir lateinit var directory: Path
    private val mapper = jacksonObjectMapper()

    @AfterEach fun cleanIdentity() { SecurityContextHolder.clearContext() }

    @Test
    fun `json and sse tool lists keep only tools the client may call`() {
        val allow = { name: String -> name == "get_history" }
        val json = """{"jsonrpc":"2.0","id":1,"result":{"tools":[{"name":"get_history"},{"name":"send_message"}]}}"""
        val rewritten = McpToolsListRewriter.rewrite(json.toByteArray(), "application/json", allow)
        val names = ObjectMapper().readTree(rewritten).path("result").path("tools").map { it.path("name").asText() }
        assertEquals(listOf("get_history"), names)

        val sse = "event: message\ndata: $json\n\n"
        val streamed = McpToolsListRewriter.rewrite(sse.toByteArray(), "text/event-stream", allow).toString(Charsets.UTF_8)
        assertTrue("get_history" in streamed)
        assertTrue("send_message" !in streamed)

        val other = """{"jsonrpc":"2.0","id":2,"result":{"content":[]}}"""
        assertEquals(other, McpToolsListRewriter.rewrite(other.toByteArray(), "application/json", allow).toString(Charsets.UTF_8))
    }

    @Test
    fun `disabled permissions leave the response unchanged`() {
        val permissions = AccessPermissionService(McpSecurityProperties(), PlatformPaths(), mapper)
        val request = MockHttpServletRequest("POST", "/mcp")
        request.setContent("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""".toByteArray())
        val response = MockHttpServletResponse()
        McpToolsListFilter(permissions).doFilter(request, response, FilterChain { _, httpResponse ->
            val body = """{"jsonrpc":"2.0","id":1,"result":{"tools":[{"name":"send_message"}]}}"""
            (httpResponse as HttpServletResponse).contentType = "application/json"
            httpResponse.outputStream.write(body.toByteArray())
        })
        assertTrue(response.contentAsString.contains("send_message"))
    }

    @Test
    fun `an enabled permission file removes tools the client cannot call`() {
        val file = directory.resolve("permissions.json")
        Files.writeString(
            file,
            mapper.writeValueAsString(PermissionDocument(1, listOf(
                PermissionGrant("reader", "work", setOf("read"), null),
            ))),
        )
        val permissions = AccessPermissionService(
            McpSecurityProperties(permissionsFile = file.toString()),
            PlatformPaths(),
            mapper,
        )
        SecurityContextHolder.getContext().authentication = ApiKeyAuthToken("reader")
        val request = MockHttpServletRequest("POST", "/mcp")
        request.setContent("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""".toByteArray())
        val response = MockHttpServletResponse()
        McpToolsListFilter(permissions).doFilter(request, response, FilterChain { httpRequest, httpResponse ->
            val downstream = httpRequest as HttpServletRequest
            assertEquals("tools/list", ObjectMapper().readTree(downstream.inputStream).path("method").asText())
            val body = """{"jsonrpc":"2.0","id":1,"result":{"tools":[{"name":"get_history"},{"name":"send_message"}]}}"""
            (httpResponse as HttpServletResponse).status = 200
            httpResponse.contentType = "application/json"
            httpResponse.outputStream.write(body.toByteArray())
        })
        val names = ObjectMapper().readTree(response.contentAsByteArray).path("result").path("tools").map { it.path("name").asText() }
        assertEquals(listOf("get_history"), names)
    }
}
