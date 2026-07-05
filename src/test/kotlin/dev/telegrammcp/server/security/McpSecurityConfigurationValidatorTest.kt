package dev.telegrammcp.server.security

import dev.telegrammcp.server.config.McpAuthMode
import dev.telegrammcp.server.config.McpSecurityProperties
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

class McpSecurityConfigurationValidatorTest {

    @Test
    fun `OAuth requires issuer resource and no API keys`() {
        val properties = McpSecurityProperties(
            security = McpSecurityProperties.SecurityProps(
                mode = McpAuthMode.OAUTH,
                apiKey = "must-not-mix",
                oauth = McpSecurityProperties.OAuthProps(
                    issuerUri = "https://issuer.example",
                    resourceUri = "https://mcp.example/mcp",
                ),
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            McpSecurityConfigurationValidator(properties).validate()
        }
    }

    @Test
    fun `OAuth rejects non HTTPS metadata`() {
        val properties = McpSecurityProperties(
            security = McpSecurityProperties.SecurityProps(
                mode = McpAuthMode.OAUTH,
                oauth = McpSecurityProperties.OAuthProps(
                    issuerUri = "http://issuer.example",
                    resourceUri = "https://mcp.example/mcp",
                ),
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            McpSecurityConfigurationValidator(properties).validate()
        }
    }

    @Test
    fun `complete external OAuth configuration is accepted`() {
        val properties = McpSecurityProperties(
            security = McpSecurityProperties.SecurityProps(
                mode = McpAuthMode.OAUTH,
                oauth = McpSecurityProperties.OAuthProps(
                    issuerUri = "https://issuer.example",
                    jwkSetUri = "https://issuer.example/jwks",
                    resourceUri = "https://mcp.example/mcp",
                ),
            ),
        )

        McpSecurityConfigurationValidator(properties).validate()
    }
}
