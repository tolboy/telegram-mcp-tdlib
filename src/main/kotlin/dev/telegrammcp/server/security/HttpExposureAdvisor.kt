package dev.telegrammcp.server.security

import dev.telegrammcp.server.config.McpAuthMode
import dev.telegrammcp.server.config.McpSecurityProperties
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

/** Warns when a static API key is the only control on a non-loopback bind. */
@Component
@Profile("!stdio")
class HttpExposureAdvisor(
    private val environment: Environment,
    private val properties: McpSecurityProperties,
) {
    private val log = LoggerFactory.getLogger(HttpExposureAdvisor::class.java)

    @PostConstruct
    fun advise() {
        val address = environment.getProperty("server.address").orEmpty().trim()
        if (address.isEmpty() || isLoopbackBind(address)) return
        if (properties.security.mode != McpAuthMode.API_KEY) return
        log.warn(
            "HTTP is bound to {} with API-key authentication. The key protects the listener. " +
                "It is not a per-user session. Use MCP_AUTH_MODE=oauth when this process is reachable " +
                "beyond the host.",
            address,
        )
    }

    companion object {
        fun isLoopbackBind(address: String): Boolean {
            val host = address.trim().removePrefix("[").removeSuffix("]")
            return host == "127.0.0.1" ||
                host.equals("localhost", ignoreCase = true) ||
                host == "::1" ||
                host == "0:0:0:0:0:0:0:1"
        }
    }
}
