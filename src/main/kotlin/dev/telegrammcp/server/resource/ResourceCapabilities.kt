package dev.telegrammcp.server.resource

import dev.telegrammcp.server.config.McpResourceProperties
import io.modelcontextprotocol.spec.McpSchema
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Spring AI always advertises `resources.subscribe = false`, and the SDK only
 * installs `resources/subscribe` handlers when the capability says true. This
 * builder replaces the auto-configured one (it is `@ConditionalOnMissingBean`)
 * and corrects the resources capability when telegram:// resources are on.
 */
@Configuration
class ResourceCapabilitiesConfig {
    @Bean
    fun telegramCapabilitiesBuilder(properties: McpResourceProperties): McpSchema.ServerCapabilities.Builder =
        ResourceCapabilitiesBuilder(properties)
}

class ResourceCapabilitiesBuilder(private val properties: McpResourceProperties) : McpSchema.ServerCapabilities.Builder() {
    override fun resources(subscribe: Boolean?, listChanged: Boolean?): McpSchema.ServerCapabilities.Builder {
        if (!properties.enabled) return super.resources(subscribe, listChanged)
        // The resource list is fixed at startup, so list_changed would never fire.
        return super.resources(properties.subscriptionsActive, false)
    }
}
