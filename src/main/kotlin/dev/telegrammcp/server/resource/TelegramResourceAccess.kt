package dev.telegrammcp.server.resource

import dev.telegrammcp.server.config.ServerModeProperties
import dev.telegrammcp.server.security.AccessPermissionService
import dev.telegrammcp.server.security.AccountAccessPolicy
import dev.telegrammcp.server.service.GuardrailService
import dev.telegrammcp.server.service.ToolSurfacePolicy
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Decides, without calling Telegram, whether the current caller could read a
 * resource through its backing read tool: the tool is on the configured
 * surface, the account is in the caller's scope, and the permission grant and
 * chat allow-list admit the chat.
 *
 * Reads still run the backing tool's full pipeline. This check gates
 * subscriptions and every outgoing change notification.
 */
@Component
class TelegramResourceAccess(
    private val accounts: AccountAccessPolicy,
    private val surface: ToolSurfacePolicy,
    private val serverMode: ServerModeProperties,
    private val guard: GuardrailService,
    private val permissions: AccessPermissionService? = null,
) {
    /** Uses the caller's current SecurityContext; STDIO has none and uses its process identity. */
    fun permits(uri: TelegramResourceUri): Boolean = permits(uri.backingTool, uri.account, uri.chatId)

    /**
     * Whether [chatId] would appear in [uri]'s own result, judged with [uri]'s
     * backing tool: for a `chats` list that is `list_chats` and its chat scope,
     * not the tool behind the chat's own URI, which may be hidden.
     */
    fun permitsChatWithin(uri: TelegramResourceUri, chatId: Long, authentication: Authentication?, now: Instant = Instant.now()): Boolean {
        if (authentication != null && !isCurrent(authentication, now)) return false
        return asAuthentication(authentication) { permits(uri.backingTool, uri.account, chatId) }
    }

    private fun permits(tool: String, account: String, chatId: Long?): Boolean = try {
        surface.isVisible(tool, serverMode.readOnly, serverMode.readOnlyAllowDownloads) &&
            accounts.selectAccount(mapOf(AccountAccessPolicy.ACCOUNT_ARGUMENT to account)) == account &&
            run {
                val chatAllowed = { chatId?.let(guard::isChatAllowed) ?: true }
                permissions?.withPermission(tool, account, chatAllowed) ?: chatAllowed()
            }
    } catch (_: Exception) {
        // Unknown accounts, denied grants and malformed state all fail closed.
        false
    }

    /** Re-evaluates [uri] for a stored subscriber. An expired OAuth token no longer authorizes anything. */
    fun permits(uri: TelegramResourceUri, authentication: Authentication?, now: Instant = Instant.now()): Boolean {
        if (authentication != null && !isCurrent(authentication, now)) return false
        return asAuthentication(authentication) { permits(uri) }
    }

    companion object {
        fun isCurrent(authentication: Authentication, now: Instant): Boolean {
            if (!authentication.isAuthenticated) return false
            val expiresAt = (authentication as? JwtAuthenticationToken)?.token?.expiresAt ?: return true
            return now.isBefore(expiresAt)
        }

        fun <T> asAuthentication(authentication: Authentication?, action: () -> T): T {
            if (authentication == null) return action()
            val previous = SecurityContextHolder.getContext()
            SecurityContextHolder.setContext(SecurityContextHolder.createEmptyContext().also { it.authentication = authentication })
            return try { action() } finally { SecurityContextHolder.setContext(previous) }
        }
    }
}
