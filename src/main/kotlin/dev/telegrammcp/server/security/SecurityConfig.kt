package dev.telegrammcp.server.security

import dev.telegrammcp.server.config.McpAuthMode
import dev.telegrammcp.server.config.McpSecurityProperties
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.authorization.AuthorizationDecision
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import java.net.InetAddress
import java.net.URI

/**
 * Stateless HTTP security for API-key and standards-based OAuth deployments.
 * STDIO uses process isolation and does not create a servlet security chain.
 */
@Configuration
@EnableWebSecurity
@Profile("!stdio")
class SecurityConfig(
    private val apiKeyAuthFilter: ApiKeyAuthFilter,
    private val properties: McpSecurityProperties,
) {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        val configured = http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth
                    .requestMatchers(
                        "/actuator/health",
                        "/actuator/health/**",
                        "/actuator/info",
                        "/.well-known/mcp-server.json",
                        "/.well-known/oauth-protected-resource",
                    ).permitAll()
                    .requestMatchers("/auth/**").access { authentication, ctx ->
                        val req = ctx.request
                        AuthorizationDecision(
                            isDirectLoopbackRequest(req) ||
                                authentication.get() !is AnonymousAuthenticationToken,
                        )
                    }
                    .requestMatchers("/setup", "/setup/**").access { _, ctx ->
                        AuthorizationDecision(isDirectLoopbackRequest(ctx.request))
                    }
                    .requestMatchers("/mcp", "/mcp/**").authenticated()
                    .requestMatchers("/actuator/**").authenticated()
                    .anyRequest().denyAll()
            }

        when (properties.security.mode) {
            McpAuthMode.API_KEY ->
                configured.addFilterBefore(apiKeyAuthFilter, UsernamePasswordAuthenticationFilter::class.java)
            McpAuthMode.OAUTH ->
                configured.oauth2ResourceServer { oauth ->
                    val converter = JwtAuthenticationConverter().also {
                        it.setPrincipalClaimName(properties.security.oauth.principalClaim)
                    }
                    oauth.jwt { jwt -> jwt.jwtAuthenticationConverter(converter) }
                    oauth.authenticationEntryPoint { _, response, _ ->
                        response.setHeader(
                            "WWW-Authenticate",
                            """Bearer resource_metadata="${oauthMetadataUri(properties.security.oauth.resourceUri)}"""",
                        )
                        response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "OAuth bearer token required")
                    }
                }
        }
        return configured.build()
    }

    companion object {
        private val FORWARDING_HEADERS = setOf(
            "Forwarded",
            "Forwarded-For",
            "X-Forwarded-For",
            "X-Forwarded-Host",
            "X-Forwarded-Port",
            "X-Forwarded-Proto",
            "X-Forwarded-Server",
            "X-Original-Forwarded-For",
            "X-Real-IP",
            "X-Client-IP",
            "Proxy-Client-IP",
            "WL-Proxy-Client-IP",
            "True-Client-IP",
            "CF-Connecting-IP",
        )

        internal fun oauthMetadataUri(resourceUri: String): String {
            val uri = URI(resourceUri)
            return URI(uri.scheme, uri.authority, "/.well-known/oauth-protected-resource", null, null).toString()
        }

        fun isDirectLoopbackRequest(request: HttpServletRequest): Boolean {
            // Loopback-only endpoints are a direct same-machine boundary. The
            // application has no configured trusted-proxy list, so accepting
            // any proxy-origin metadata would make that boundary spoofable
            // through a local reverse proxy. Header presence is enough to
            // reject, including blank or duplicate values.
            return isLoopbackAddress(request.remoteAddr) &&
                FORWARDING_HEADERS.none { request.getHeaders(it).hasMoreElements() }
        }

        fun isLoopbackAddress(remoteAddr: String): Boolean {
            val parsed = parseIpLiteral(remoteAddr) ?: return false
            return parsed.isLoopbackAddress
        }

        private fun parseIpLiteral(value: String): InetAddress? {
            val normalized = normalizeIpCandidate(value) ?: return null
            if (!isIpLiteral(normalized)) return null
            return runCatching { InetAddress.getByName(normalized) }.getOrNull()
        }

        private fun normalizeIpCandidate(value: String): String? {
            val trimmed = value.trim().trim('"')
            if (trimmed.isEmpty()) return null

            if (trimmed.startsWith('[')) {
                val end = trimmed.indexOf(']')
                if (end <= 1) return null
                return trimmed.substring(1, end)
            }

            if (trimmed.count { it == ':' } == 1 && '.' in trimmed) {
                val host = trimmed.substringBefore(':').trim()
                val port = trimmed.substringAfter(':').trim()
                if (host.isEmpty() || port.isEmpty() || !port.all(Char::isDigit)) return null
                val portInt = port.toIntOrNull() ?: return null
                if (portInt !in 0..65535) return null
                return host
            }
            return trimmed
        }

        private fun isIpLiteral(value: String): Boolean =
            isIpv4Literal(value) || isIpv6Literal(value)

        private fun isIpv4Literal(value: String): Boolean {
            val parts = value.split('.')
            if (parts.size != 4) return false
            return parts.all { part ->
                part.isNotEmpty() &&
                    part.length <= 3 &&
                    part.all(Char::isDigit) &&
                    part.toIntOrNull()?.let { it in 0..255 } == true
            }
        }

        private fun isIpv6Literal(value: String): Boolean {
            val colonCount = value.count { it == ':' }
            if (colonCount !in 2..7) return false
            return value.all { ch -> ch == ':' || ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F' }
        }
    }
}
