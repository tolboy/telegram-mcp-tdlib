package dev.telegrammcp.server.security

import dev.telegrammcp.server.config.McpSecurityProperties
import dev.telegrammcp.server.config.McpAuthMode
import dev.telegrammcp.server.auth.AuthWizardProperties
import jakarta.annotation.PostConstruct
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Extracts and validates an API key from incoming requests.
 *
 * Supports two header formats:
 * - `Authorization: Bearer <key>`
 * - `X-MCP-API-Key: <key>`
 *
 * When the configured key is blank the filter is a no-op (useful for local dev).
 */
@Component
class ApiKeyAuthFilter(
    private val props: McpSecurityProperties,
    private val secretResolver: SecretResolver,
    private val authWizard: AuthWizardProperties = AuthWizardProperties(),
) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(ApiKeyAuthFilter::class.java)
    private val resolvedKeys: List<ConfiguredKey> by lazy(::buildConfiguredKeys)

    /** Fail fast for malformed secret files and duplicate client IDs. */
    @PostConstruct
    fun validateConfiguration() {
        resolvedKeys
    }

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = requestPath(request)
        if (props.security.mode == McpAuthMode.OAUTH && !authWizard.enabled) return true
        return when {
            path == "/actuator/health" || path.startsWith("/actuator/health/") -> true
            path == "/actuator/info" -> true
            path == "/.well-known/mcp-server.json" -> true
            path.startsWith("/auth/") -> !authWizard.enabled && isLoopbackRequest(request)
            path == "/mcp" || path.startsWith("/mcp/") -> false
            path.startsWith("/actuator/") -> false
            else -> true
        }
    }

    /**
     * Streamable HTTP completes session-bound MCP messages on an async
     * dispatch. Re-authenticate that dispatch because the stateless security
     * context from the initial servlet thread is no longer available.
     */
    override fun shouldNotFilterAsyncDispatch(): Boolean = false

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val configuredKeys = resolvedKeys
        val authEndpoint = requestPath(request).startsWith("/auth/")

        if (authEndpoint && authWizard.enabled) {
            val suppliedNonce = request.getHeader("X-Auth-Wizard-Nonce").orEmpty()
            if (!isLoopbackRequest(request) ||
                authWizard.nonce.isBlank() ||
                !constantTimeEquals(suppliedNonce, authWizard.nonce)
            ) {
                SecurityContextHolder.clearContext()
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "Invalid authentication wizard nonce")
                return
            }
            SecurityContextHolder.getContext().authentication = ApiKeyAuthToken("auth-wizard")
            filterChain.doFilter(request, response)
            return
        }

        // Loopback auth requests were skipped above. A non-loopback auth
        // request must never inherit the keyless local-development behavior.
        if (authEndpoint && configuredKeys.isEmpty()) {
            SecurityContextHolder.clearContext()
            response.sendError(
                HttpServletResponse.SC_FORBIDDEN,
                "Interactive auth is limited to loopback unless an MCP API key is configured",
            )
            return
        }

        // Skip auth when no key is configured (local MCP development only).
        if (configuredKeys.isEmpty()) {
            SecurityContextHolder.getContext().authentication = ApiKeyAuthToken("local-dev")
            filterChain.doFilter(request, response)
            return
        }

        val providedKey = extractKey(request)

        val matchedClient = providedKey?.let { supplied ->
            configuredKeys.firstOrNull { candidate -> constantTimeEquals(supplied, candidate.key) }
        }
        if (matchedClient != null) {
            SecurityContextHolder.getContext().authentication = ApiKeyAuthToken(
                principal = matchedClient.id,
                allowedAccounts = matchedClient.allowedAccounts,
            )
            filterChain.doFilter(request, response)
        } else {
            SecurityContextHolder.clearContext()
            log.warn("Rejected request to {} — invalid or missing API key", request.requestURI)
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid or missing API key")
        }
    }

    private fun extractKey(request: HttpServletRequest): String? {
        val preferredHeader = props.security.headerName.trim().ifBlank { HttpHeaders.AUTHORIZATION }
        val headerCandidates = buildList {
            add(preferredHeader)
            if (!preferredHeader.equals("X-MCP-API-Key", ignoreCase = true)) {
                add("X-MCP-API-Key")
            }
            if (!preferredHeader.equals(HttpHeaders.AUTHORIZATION, ignoreCase = true)) {
                add(HttpHeaders.AUTHORIZATION)
            }
        }

        return headerCandidates
            .asSequence()
            .mapNotNull { headerName -> extractKeyFromHeader(request, headerName) }
            .firstOrNull()
    }

    private fun extractKeyFromHeader(request: HttpServletRequest, headerName: String): String? {
        val rawValue = request.getHeader(headerName)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val bearerValue = rawValue
            .takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substring(7)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        return bearerValue ?: rawValue.takeUnless {
            headerName.equals(HttpHeaders.AUTHORIZATION, ignoreCase = true)
        }
    }

    private fun requestPath(request: HttpServletRequest): String {
        val contextPath = request.contextPath.orEmpty()
        return request.requestURI.removePrefix(contextPath)
    }

    private fun isLoopbackRequest(request: HttpServletRequest): Boolean =
        SecurityConfig.isLoopbackRequest(request.remoteAddr, request.getHeader("X-Forwarded-For"))

    private fun constantTimeEquals(provided: String, expected: String): Boolean =
        MessageDigest.isEqual(
            provided.toByteArray(StandardCharsets.UTF_8),
            expected.toByteArray(StandardCharsets.UTF_8),
        )

    private fun buildConfiguredKeys(): List<ConfiguredKey> = buildList {
        val globalKey = secretResolver.resolve(
            directValue = props.security.apiKey,
            fileName = props.security.apiKeyFile,
            settingName = "MCP API key",
        )
        if (globalKey.isNotBlank()) add(ConfiguredKey("mcp-client", globalKey, null))

        val ids = mutableSetOf<String>()
        props.security.clients.forEach { client ->
            val id = client.id.trim()
            require(id.isNotBlank()) { "Every mcp.security.clients entry requires a non-blank id" }
            require(ids.add(id)) { "Duplicate MCP client id '$id'" }
            val key = secretResolver.resolve(
                directValue = client.apiKey,
                fileName = client.apiKeyFile,
                settingName = "MCP client '$id' API key",
                required = true,
            )
            val scopes = client.allowedAccounts.takeIf { it.isNotEmpty() }
                ?.let(AccountAccessPolicy::normalizeScopes)
            add(ConfiguredKey(id, key, scopes))
        }
    }

    private data class ConfiguredKey(
        val id: String,
        val key: String,
        val allowedAccounts: Set<String>?,
    )
}

/**
 * Lightweight authentication token representing a validated API key.
 */
class ApiKeyAuthToken(
    private val principal: String,
    val allowedAccounts: Set<String>? = null,
) : AbstractAuthenticationToken(listOf(SimpleGrantedAuthority("ROLE_MCP_CLIENT"))) {

    init {
        isAuthenticated = true
    }

    override fun getCredentials(): Any? = null
    override fun getPrincipal(): Any = principal
}
