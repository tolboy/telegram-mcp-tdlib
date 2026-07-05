package dev.telegrammcp.server.security

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
    ): ApiKeyAuthFilter {
        val props = McpSecurityProperties(
            security = McpSecurityProperties.SecurityProps(
                apiKey = apiKey,
                headerName = headerName,
                clients = clients,
            ),
        )
        return ApiKeyAuthFilter(props, SecretResolver(PlatformPaths()))
    }
}
