package dev.telegrammcp.server.security

import dev.telegrammcp.server.config.McpAuthMode
import dev.telegrammcp.server.config.McpSecurityProperties
import dev.telegrammcp.server.service.PlatformPaths
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import jakarta.servlet.DispatcherType
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ApiKeyAuthFilterTest {

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `public health endpoint bypasses authentication even when key is configured`() {
        val filter = createFilter()
        val request = MockHttpServletRequest("GET", "/actuator/health")
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        assertEquals(200, response.status)
        assertNotNull(chain.request)
        assertNull(SecurityContextHolder.getContext().authentication)
    }

    @Test
    fun `well known descriptor bypasses authentication even when key is configured`() {
        val filter = createFilter()
        val request = MockHttpServletRequest("GET", "/.well-known/mcp-server.json")
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        assertEquals(200, response.status)
        assertNotNull(chain.request)
        assertNull(SecurityContextHolder.getContext().authentication)
    }

    @Test
    fun `protected endpoint accepts bearer token`() {
        val filter = createFilter()
        val request = MockHttpServletRequest("GET", "/mcp/message")
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()
        request.addHeader("Authorization", "Bearer secret-key")

        filter.doFilter(request, response, chain)

        assertEquals(200, response.status)
        assertNotNull(chain.request)
        val authentication = assertNotNull(SecurityContextHolder.getContext().authentication)
        assertEquals("mcp-client", authentication.principal)
    }

    @Test
    fun `async MCP dispatch retains API-key authentication`() {
        val filter = createFilter()
        val request = MockHttpServletRequest("POST", "/mcp")
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()
        request.dispatcherType = DispatcherType.ASYNC
        request.addHeader("Authorization", "Bearer secret-key")

        filter.doFilter(request, response, chain)

        assertNotNull(chain.request)
        assertEquals("mcp-client", assertNotNull(SecurityContextHolder.getContext().authentication).principal)
    }

    @Test
    fun `protected endpoint accepts configured custom header`() {
        val filter = createFilter(headerName = "X-Internal-Api-Key")
        val request = MockHttpServletRequest("GET", "/mcp/message")
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()
        request.addHeader("X-Internal-Api-Key", "secret-key")

        filter.doFilter(request, response, chain)

        assertEquals(200, response.status)
        assertNotNull(chain.request)
        val authentication = assertNotNull(SecurityContextHolder.getContext().authentication)
        assertEquals("mcp-client", authentication.principal)
    }

    @Test
    fun `protected endpoint still accepts X-MCP-API-Key fallback`() {
        val filter = createFilter(headerName = "X-Internal-Api-Key")
        val request = MockHttpServletRequest("GET", "/actuator/prometheus")
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()
        request.addHeader("X-MCP-API-Key", "secret-key")

        filter.doFilter(request, response, chain)

        assertEquals(200, response.status)
        assertNotNull(chain.request)
        val authentication = assertNotNull(SecurityContextHolder.getContext().authentication)
        assertEquals("mcp-client", authentication.principal)
    }

    @Test
    fun `protected endpoint rejects missing key`() {
        val filter = createFilter()
        val request = MockHttpServletRequest("GET", "/mcp/message")
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        assertEquals(401, response.status)
        assertNull(chain.request)
        assertNull(SecurityContextHolder.getContext().authentication)
    }

    @Test
    fun `keyless api key mode authenticates loopback MCP requests for local development`() {
        val filter = createFilter(apiKey = "")
        val request = MockHttpServletRequest("POST", "/mcp")
        request.remoteAddr = "127.0.0.1"
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        assertEquals(200, response.status)
        assertNotNull(chain.request)
        assertEquals("local-dev", assertNotNull(SecurityContextHolder.getContext().authentication).principal)
    }

    @Test
    fun `keyless api key mode rejects non-loopback MCP requests`() {
        val filter = createFilter(apiKey = "")
        val request = MockHttpServletRequest("POST", "/mcp")
        request.remoteAddr = "192.0.2.10"
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        assertEquals(403, response.status)
        assertNull(chain.request)
        assertNull(SecurityContextHolder.getContext().authentication)
    }

    @Test
    fun `keyless api key mode rejects forwarded requests even through loopback`() {
        val filter = createFilter(apiKey = "")
        val request = MockHttpServletRequest("POST", "/mcp")
        request.remoteAddr = "127.0.0.1"
        request.addHeader("X-Forwarded-For", "127.0.0.1, 203.0.113.50")
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        assertEquals(403, response.status)
        assertNull(chain.request)
        assertNull(SecurityContextHolder.getContext().authentication)
    }

    @Test
    fun `keyless api key mode rejects alternative and duplicate forwarding headers`() {
        listOf(
            listOf("Forwarded" to "for=203.0.113.50"),
            listOf("X-Real-IP" to "203.0.113.50"),
            listOf("X-Forwarded-For" to "", "X-Forwarded-For" to "203.0.113.50"),
        ).forEach { headers ->
            val filter = createFilter(apiKey = "")
            val request = MockHttpServletRequest("POST", "/mcp")
            request.remoteAddr = "127.0.0.1"
            headers.forEach { (name, value) -> request.addHeader(name, value) }
            val response = MockHttpServletResponse()
            val chain = MockFilterChain()

            filter.doFilter(request, response, chain)

            assertEquals(403, response.status, "Accepted forwarding headers: $headers")
            assertNull(chain.request)
            assertNull(SecurityContextHolder.getContext().authentication)
        }
    }

    @Test
    fun `keyless api key mode rejects non-loopback protected actuator requests`() {
        val filter = createFilter(apiKey = "")
        val request = MockHttpServletRequest("GET", "/actuator/prometheus")
        request.remoteAddr = "198.51.100.20"
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        assertEquals(403, response.status)
        assertNull(chain.request)
        assertNull(SecurityContextHolder.getContext().authentication)
    }

    @Test
    fun `configured API key authenticates non-loopback MCP requests`() {
        val filter = createFilter()
        val request = MockHttpServletRequest("POST", "/mcp")
        request.remoteAddr = "203.0.113.30"
        request.addHeader("Authorization", "Bearer secret-key")
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        assertEquals(200, response.status)
        assertNotNull(chain.request)
        assertEquals("mcp-client", assertNotNull(SecurityContextHolder.getContext().authentication).principal)
    }

    @Test
    fun `OAuth mode leaves non-loopback MCP requests to the OAuth resource server`() {
        val filter = createFilter(apiKey = "", mode = McpAuthMode.OAUTH)
        val request = MockHttpServletRequest("POST", "/mcp")
        request.remoteAddr = "203.0.113.40"
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        assertEquals(200, response.status)
        assertNotNull(chain.request)
        assertNull(SecurityContextHolder.getContext().authentication)
    }

    @Test
    fun `loopback auth endpoint bypasses authentication`() {
        val filter = createFilter()
        val request = MockHttpServletRequest("GET", "/auth/state")
        request.remoteAddr = "127.0.0.1"
        val chain = MockFilterChain()

        filter.doFilter(request, MockHttpServletResponse(), chain)

        assertNotNull(chain.request)
        assertNull(SecurityContextHolder.getContext().authentication)
    }

    @Test
    fun `private network auth endpoint requires configured key`() {
        val filter = createFilter(apiKey = "")
        val request = MockHttpServletRequest("GET", "/auth/state")
        request.remoteAddr = "172.17.0.1"
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        assertEquals(403, response.status)
        assertNull(chain.request)
    }

    @Test
    fun `private network auth endpoint accepts configured key`() {
        val filter = createFilter()
        val request = MockHttpServletRequest("GET", "/auth/state")
        request.remoteAddr = "172.17.0.1"
        request.addHeader("Authorization", "Bearer secret-key")
        val chain = MockFilterChain()

        filter.doFilter(request, MockHttpServletResponse(), chain)

        assertNotNull(chain.request)
        assertEquals("mcp-client", assertNotNull(SecurityContextHolder.getContext().authentication).principal)
    }

    @Test
    fun `named client key carries its account scope into authentication`() {
        val filter = createFilter(
            apiKey = "",
            clients = listOf(
                McpSecurityProperties.ClientKeyProps(
                    id = "work-agent",
                    apiKey = "work-key",
                    allowedAccounts = listOf("work"),
                ),
            ),
        )
        val request = MockHttpServletRequest("GET", "/mcp")
        val response = MockHttpServletResponse()
        request.addHeader("Authorization", "Bearer work-key")

        filter.doFilter(request, response, MockFilterChain())

        val token = SecurityContextHolder.getContext().authentication as ApiKeyAuthToken
        assertEquals("work-agent", token.principal)
        assertEquals(setOf("work"), token.allowedAccounts)
    }

    private fun createFilter(
        apiKey: String = "secret-key",
        headerName: String = "Authorization",
        clients: List<McpSecurityProperties.ClientKeyProps> = emptyList(),
        mode: McpAuthMode = McpAuthMode.API_KEY,
    ): ApiKeyAuthFilter {
        val props = McpSecurityProperties(
            security = McpSecurityProperties.SecurityProps(
                mode = mode,
                apiKey = apiKey,
                headerName = headerName,
                clients = clients,
            ),
        )
        return ApiKeyAuthFilter(props, SecretResolver(PlatformPaths()))
    }
}
