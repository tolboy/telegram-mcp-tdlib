package dev.telegrammcp.server.security

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpRoutingHeaderPolicyTest {
    private val mapper = ObjectMapper()

    @Test
    fun `missing routing headers are not a reason to read or reject the body`() {
        val request = MockHttpServletRequest("POST", "/mcp")
        request.setContent("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""".toByteArray())
        val response = MockHttpServletResponse()
        var continued = false
        McpRoutingHeaderFilter().doFilter(request, response, FilterChain { _, _ -> continued = true })
        assertTrue(continued)
        assertEquals(200, response.status)
    }

    @Test
    fun `a present method header must match the body and a matching name may be base64`() {
        val body = """{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"get_me"}}"""
        assertNull(McpRoutingHeaderPolicy.evaluate("tools/call", null, body.toByteArray(), mapper))
        val encoded = "=?base64?${Base64.getEncoder().encodeToString("get_me".toByteArray())}?="
        assertNull(McpRoutingHeaderPolicy.evaluate("tools/call", encoded, body.toByteArray(), mapper))

        val mismatch = McpRoutingHeaderPolicy.evaluate("tools/list", "get_me", body.toByteArray(), mapper)
        val error = mapper.readTree(mismatch)
        assertEquals(-32020, error.path("error").path("code").asInt())
        assertEquals(7, error.path("id").asInt())
        assertTrue(error.path("error").path("message").asText().contains("Mcp-Method"))
    }

    @Test
    fun `a mismatched name is rejected and an absent name stays allowed`() {
        val body = """{"jsonrpc":"2.0","id":"a","method":"prompts/get","params":{"name":"summarize-inbox"}}"""
        assertNull(McpRoutingHeaderPolicy.evaluate(null, null, body.toByteArray(), mapper))
        val mismatch = McpRoutingHeaderPolicy.evaluate(null, "other", body.toByteArray(), mapper)
        assertEquals("a", mapper.readTree(mismatch).path("id").asText())
        assertTrue(mapper.readTree(mismatch).path("error").path("message").asText().contains("Mcp-Name"))
    }

    @Test
    fun `the filter returns the header mismatch and does not call the chain`() {
        val request = MockHttpServletRequest("POST", "/mcp")
        request.addHeader("Mcp-Method", "tools/call")
        request.setContent("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""".toByteArray())
        val response = MockHttpServletResponse()
        var continued = false
        McpRoutingHeaderFilter().doFilter(request, response, FilterChain { _, _ -> continued = true })
        assertTrue(!continued)
        assertEquals(400, response.status)
        assertEquals(-32020, ObjectMapper().readTree(response.contentAsByteArray).path("error").path("code").asInt())
    }
}
