package dev.telegrammcp.server.auth

import dev.telegrammcp.server.client.TelegramAccountRegistry
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import kotlin.test.assertEquals

@SpringBootTest(
    properties = [
        "spring.ai.mcp.server.enabled=false",
        "auth-wizard.enabled=true",
        "auth-wizard.nonce=test-only-nonce",
        "auth-wizard.account-label=work",
        "auth-wizard.method=qr",
    ],
)
@ActiveProfiles("test", "auth-wizard")
class AuthWizardContextTest {

    @Autowired
    private lateinit var registry: TelegramAccountRegistry

    @Autowired
    private lateinit var properties: AuthWizardProperties

    @Test
    fun `auth wizard boots with isolated selected account`() {
        assertEquals(listOf("work"), registry.labels())
        assertEquals("test-only-nonce", properties.nonce)
    }
}
