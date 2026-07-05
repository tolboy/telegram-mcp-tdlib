package dev.telegrammcp.server.security

import dev.telegrammcp.server.client.TelegramAccountRegistry
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.config.McpAuthMode
import dev.telegrammcp.server.config.McpSecurityProperties
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import kotlin.test.assertEquals

class AccountAccessPolicyTest {

    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `single account is selected when omitted`() {
        val policy = policyFor("personal")
        assertEquals("personal", policy.selectAccount(emptyMap()))
    }

    @Test
    fun `multi account calls require explicit account`() {
        val policy = policyFor("personal", "work")
        val ex = assertThrows<IllegalArgumentException> { policy.selectAccount(emptyMap()) }
        assertEquals(true, ex.message!!.contains("'account' is required"))
    }

    @Test
    fun `scoped API key cannot select a different account`() {
        val policy = policyFor("personal", "work")
        SecurityContextHolder.getContext().authentication = ApiKeyAuthToken("work-agent", setOf("work"))

        assertEquals("work", policy.selectAccount(mapOf("account" to "WORK")))
        assertThrows<IllegalArgumentException> {
            policy.selectAccount(mapOf("account" to "personal"))
        }
        assertEquals(listOf("work"), policy.visibleAccounts())
    }

    @Test
    fun `OAuth account claim uses the same fail closed account scope`() {
        val registry = registryFor("personal", "work")
        val properties = McpSecurityProperties(
            security = McpSecurityProperties.SecurityProps(
                mode = McpAuthMode.OAUTH,
                oauth = McpSecurityProperties.OAuthProps(
                    issuerUri = "https://issuer.example",
                    resourceUri = "https://mcp.example/mcp",
                ),
            ),
        )
        val jwt = Jwt.withTokenValue("token")
            .header("alg", "none")
            .subject("agent")
            .claim("telegram_accounts", listOf("work"))
            .build()
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
        val policy = AccountAccessPolicy(registry, properties)

        assertEquals("work", policy.selectAccount(mapOf("account" to "work")))
        assertThrows<IllegalArgumentException> {
            policy.selectAccount(mapOf("account" to "personal"))
        }
        assertEquals(listOf("work"), policy.visibleAccounts())
    }

    private fun policyFor(vararg labels: String): AccountAccessPolicy {
        return AccountAccessPolicy(registryFor(*labels))
    }

    private fun registryFor(vararg labels: String): TelegramAccountRegistry {
        val registry = TelegramAccountRegistry()
        labels.forEach { label ->
            registry.register(TelegramAccountRegistry.AccountHandle(label, mockk<TelegramClientService>(relaxed = true)))
        }
        return registry
    }
}
