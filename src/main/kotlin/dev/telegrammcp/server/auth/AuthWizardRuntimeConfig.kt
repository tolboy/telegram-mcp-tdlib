package dev.telegrammcp.server.auth

import dev.telegrammcp.server.client.DelegatingTelegramClientService
import dev.telegrammcp.server.client.TelegramAccountContext
import dev.telegrammcp.server.client.TelegramAccountRegistry
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

/** Minimal account registry used while the standalone auth wizard owns TDLib. */
@Configuration(proxyBeanMethods = false)
@Profile("auth-wizard")
class AuthWizardRuntimeConfig {

    @Bean
    fun authWizardAccountRegistry(
        delegating: DelegatingTelegramClientService,
        properties: AuthWizardProperties,
    ): TelegramAccountRegistry =
        TelegramAccountRegistry().also { registry ->
            registry.register(
                TelegramAccountRegistry.AccountHandle(
                    properties.accountLabel,
                    delegating.proxy,
                ),
            )
        }

    @Bean
    fun authWizardAccountContext(registry: TelegramAccountRegistry): TelegramAccountContext =
        TelegramAccountContext(registry)
}
