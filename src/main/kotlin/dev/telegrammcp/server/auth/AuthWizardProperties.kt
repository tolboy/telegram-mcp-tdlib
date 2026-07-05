package dev.telegrammcp.server.auth

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "auth-wizard")
data class AuthWizardProperties(
    val enabled: Boolean = false,
    val nonce: String = "",
    val accountLabel: String = "default",
    val method: String = "qr",
)
