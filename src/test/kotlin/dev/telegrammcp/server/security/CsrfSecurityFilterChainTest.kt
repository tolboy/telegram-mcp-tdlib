package dev.telegrammcp.server.security

import dev.telegrammcp.server.auth.AuthWizardProperties
import dev.telegrammcp.server.config.McpAuthMode
import dev.telegrammcp.server.config.McpSecurityProperties
import dev.telegrammcp.server.service.PlatformPaths
import jakarta.servlet.Filter
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockServletContext
import org.springframework.security.oauth2.jwt.BadJwtException
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext
import kotlin.test.assertEquals

/** Exercise the real security chain, including authentication/CSRF filter ordering. */
class CsrfSecurityFilterChainTest {
    @Test
    fun `loopback form posts cannot log out or invoke keyless MCP`() = withChain(apiKey = "") { chain ->
        for (path in listOf("/auth/logout", "/auth/resend-code", "/mcp")) {
            assertEquals(403, request(chain, path, origin = "https://attacker.example"))
            assertEquals(403, request(chain, path)) // Origin/Referer can be absent.
        }
        assertEquals(204, request(chain, "/auth/logout", csrfToken = true))
        assertEquals(204, request(chain, "/mcp", csrfToken = true))
        assertEquals(204, request(chain, "/actuator/health", method = "GET"))
    }

    @Test
    fun `validated API headers work without CSRF and forged credentials fail`() = withChain { chain ->
        for (path in listOf("/mcp", "/auth/logout")) {
            assertEquals(204, request(chain, path, "Authorization" to "Bearer secret-key"))
            assertEquals(204, request(chain, path, "X-MCP-API-Key" to "secret-key"))
            assertEquals(401, request(chain, path, "Authorization" to "Bearer wrong"))
            assertEquals(401, request(chain, path, "X-MCP-API-Key" to "wrong"))
            assertEquals(if (path == "/mcp") 401 else 403, request(chain, path, "X-Auth-Wizard-Nonce" to "forged"),
                "A forged wizard nonce must not exempt an unauthenticated auth request")
        }
    }

    @Test
    fun `wizard nonce remains required and works without an additional CSRF token`() =
        withChain(wizard = AuthWizardProperties(enabled = true, nonce = "wizard-secret")) { chain ->
            assertEquals(204, request(chain, "/auth/logout", "X-Auth-Wizard-Nonce" to "wizard-secret"))
            assertEquals(403, request(chain, "/auth/logout", "X-Auth-Wizard-Nonce" to "wrong"))
            assertEquals(403, request(chain, "/auth/logout"))
        }

    @Test
    fun `OAuth bearer requests work but missing and invalid tokens cannot bypass protection`() =
        withChain(mode = McpAuthMode.OAUTH) { chain ->
            assertEquals(204, request(chain, "/mcp", "Authorization" to "Bearer valid-jwt"))
            assertEquals(401, request(chain, "/mcp", "Authorization" to "Bearer invalid-jwt"))
            assertEquals(403, request(chain, "/auth/logout"))
            assertEquals(401, request(chain, "/auth/logout", "Authorization" to "Bearer invalid-jwt"))
        }

    private fun withChain(
        apiKey: String = "secret-key",
        mode: McpAuthMode = McpAuthMode.API_KEY,
        wizard: AuthWizardProperties = AuthWizardProperties(),
        test: (Filter) -> Unit,
    ) {
        AnnotationConfigWebApplicationContext().use { context ->
            context.servletContext = MockServletContext()
            val props = McpSecurityProperties(security = McpSecurityProperties.SecurityProps(
                apiKey = apiKey, mode = mode,
                oauth = McpSecurityProperties.OAuthProps(resourceUri = "https://mcp.example/mcp"),
            ))
            context.addBeanFactoryPostProcessor { factory ->
                factory.registerSingleton("mcpProperties", props)
                factory.registerSingleton("apiKeyAuthFilter", ApiKeyAuthFilter(props, SecretResolver(PlatformPaths()), wizard))
                factory.registerSingleton("jwtDecoder", JwtDecoder { token ->
                    if (token != "valid-jwt") throw BadJwtException("Invalid test token")
                    Jwt.withTokenValue(token).header("alg", "RS256").subject("test-client").build()
                })
            }
            context.register(SecurityConfig::class.java)
            context.refresh()
            test(context.getBean("springSecurityFilterChain", Filter::class.java))
        }
    }

    private fun request(
        chain: Filter,
        path: String,
        header: Pair<String, String>? = null,
        method: String = "POST",
        origin: String? = null,
        csrfToken: Boolean = false,
    ): Int {
        val request = MockHttpServletRequest(method, path).also {
            it.servletPath = path
            it.remoteAddr = "127.0.0.1"
            it.contentType = "application/x-www-form-urlencoded"
            header?.let { (name, value) -> it.addHeader(name, value) }
            origin?.let { value -> it.addHeader("Origin", value) }
        }
        val response = MockHttpServletResponse()
        if (csrfToken) {
            val repository = HttpSessionCsrfTokenRepository()
            val token = repository.generateToken(request)
            repository.saveToken(token, request, response)
            XorCsrfTokenRequestAttributeHandler().handle(request, response) { token }
            val maskedToken = request.getAttribute(CsrfToken::class.java.name) as CsrfToken
            request.addHeader(token.headerName, maskedToken.token)
        }
        chain.doFilter(request, response) { _, _ -> response.status = 204 }
        return response.status
    }
}
