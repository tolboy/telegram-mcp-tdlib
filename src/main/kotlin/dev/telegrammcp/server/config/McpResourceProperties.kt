package dev.telegrammcp.server.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * MCP resources and resource subscriptions (`mcp.resources.*`).
 *
 * Resources are off by default. Earlier reviews rejected exposing chat text as
 * resources because a host may attach or prefetch resources without a tool
 * call, which widens where conversation text ends up. Operators opt in.
 */
@ConfigurationProperties(prefix = "mcp.resources")
data class McpResourceProperties(
    /** Registers the telegram:// resources and templates. */
    val enabled: Boolean = false,
    /** Advertises `resources.subscribe` and sends `notifications/resources/updated`. */
    val subscriptions: Boolean = true,
    /** Changes to one URI inside this window produce one notification. */
    val debounce: Duration = Duration.ofSeconds(1),
    /** Subscriptions one MCP session may hold. */
    val maxSubscriptionsPerSession: Int = 50,
) {
    init {
        require(!debounce.isNegative && debounce <= Duration.ofMinutes(1)) { "mcp.resources.debounce must be in 0..60s" }
        require(maxSubscriptionsPerSession in 1..1000) { "mcp.resources.max-subscriptions-per-session must be in 1..1000" }
    }

    /** Subscriptions only exist on top of registered resources. */
    val subscriptionsActive: Boolean get() = enabled && subscriptions
}
