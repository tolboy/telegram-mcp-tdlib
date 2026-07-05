package dev.telegrammcp.server.security

import dev.telegrammcp.server.config.McpSecurityProperties
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtDecoders
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder

/** JWT validation against an external OAuth/OIDC authorization server. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "mcp.security", name = ["mode"], havingValue = "oauth")
@Profile("!stdio")
class OAuthResourceServerConfig {

    @Bean
    fun jwtDecoder(properties: McpSecurityProperties): JwtDecoder {
        val oauth = properties.security.oauth
        val decoder = if (oauth.jwkSetUri.isNotBlank()) {
            NimbusJwtDecoder.withJwkSetUri(oauth.jwkSetUri).build()
        } else {
            JwtDecoders.fromIssuerLocation(oauth.issuerUri) as NimbusJwtDecoder
        }
        val issuerValidator = JwtValidators.createDefaultWithIssuer(oauth.issuerUri)
        val audienceValidator = OAuth2TokenValidator<Jwt> { jwt ->
            if (oauth.resourceUri in jwt.audience.orEmpty()) {
                OAuth2TokenValidatorResult.success()
            } else {
                OAuth2TokenValidatorResult.failure(
                    OAuth2Error(
                        "invalid_token",
                        "Token audience does not contain the configured MCP resource URI",
                        null,
                    ),
                )
            }
        }
        decoder.setJwtValidator(DelegatingOAuth2TokenValidator(issuerValidator, audienceValidator))
        return decoder
    }
}
