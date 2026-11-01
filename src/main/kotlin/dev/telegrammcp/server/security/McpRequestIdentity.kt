package dev.telegrammcp.server.security

import io.modelcontextprotocol.common.McpTransportContext
import io.modelcontextprotocol.server.McpSyncServerExchange
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder

/** Carries server-validated authority across the SDK's boundedElastic boundary. */
object McpRequestIdentity {
    private const val AUTHENTICATION = "dev.telegrammcp.authenticated-principal"

    fun capture(): McpTransportContext {
        val authentication = checkNotNull(SecurityContextHolder.getContext().authentication) {
            "An authenticated HTTP request is required"
        }
        check(authentication.isAuthenticated) { "An authenticated HTTP request is required" }
        return McpTransportContext.create(mapOf(AUTHENTICATION to authentication))
    }

    fun <T> withIdentity(exchange: McpSyncServerExchange, action: () -> T): T =
        withIdentity(exchange.transportContext(), action)

    fun <T> withIdentity(context: McpTransportContext?, action: () -> T): T {
        // STDIO carries no HTTP authority and keeps its process-scoped identity.
        val authentication = context?.get(AUTHENTICATION) as? Authentication ?: return action()
        val previous = SecurityContextHolder.getContext()
        SecurityContextHolder.setContext(SecurityContextHolder.createEmptyContext().also {
            it.authentication = authentication
        })
        return try { action() } finally { SecurityContextHolder.setContext(previous) }
    }
}
