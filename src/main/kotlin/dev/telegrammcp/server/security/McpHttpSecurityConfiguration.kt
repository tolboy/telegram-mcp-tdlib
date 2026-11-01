package dev.telegrammcp.server.security

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper
import org.springframework.ai.mcp.customizer.McpSyncServerCustomizer
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.context.annotation.Primary
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import tools.jackson.databind.json.JsonMapper

@Configuration
class McpHttpSecurityConfiguration {
    @Bean
    @Profile("!stdio")
    @ConditionalOnProperty(prefix = "spring.ai.mcp.server", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    fun webMvcStreamableServerTransportProvider(
        @Qualifier("mcpServerJsonMapper") mapper: JsonMapper,
        properties: McpServerStreamableHttpProperties,
    ): WebMvcStreamableServerTransportProvider = WebMvcStreamableServerTransportProvider.builder()
        .jsonMapper(JacksonMcpJsonMapper(mapper))
        .mcpEndpoint(properties.mcpEndpoint)
        .keepAliveInterval(properties.keepAliveInterval)
        .disallowDelete(properties.isDisallowDelete)
        .sessionIdleTimeout(McpSessionPrincipalBinder.IDLE_TIMEOUT)
        .maxSessions(McpSessionPrincipalBinder.MAX_SESSIONS.toLong())
        .contextExtractor { McpRequestIdentity.capture() }
        .build()

    @Bean
    @Primary
    fun permissionToolFilter(
        permissions: AccessPermissionService,
        accounts: AccountAccessPolicy,
        environment: Environment,
    ): McpSyncServerCustomizer = McpSyncServerCustomizer { builder ->
        // Spring AI's servlet customizer also does this. It accepts only one
        // customizer, so retain that behavior in our primary customization.
        if (!environment.acceptsProfiles(Profiles.of("stdio"))) builder.immediateExecution(true)
        builder.addToolFilter { context, tool ->
            McpRequestIdentity.withIdentity(context) {
                permissions.allowsListedTool(tool.name(), accounts.visibleAccounts().toSet())
            }
        }
    }
}
