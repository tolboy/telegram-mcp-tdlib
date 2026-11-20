package dev.telegrammcp.server.service

import dev.telegrammcp.server.config.McpSecurityProperties
import dev.telegrammcp.server.config.TelegramProperties
import dev.telegrammcp.server.exception.ChatNotAllowedException
import dev.telegrammcp.server.exception.GuardrailViolationException
import dev.telegrammcp.server.exception.InvalidToolInputException
import dev.telegrammcp.server.security.SensitiveChatBlockedException
import dev.telegrammcp.server.security.SensitiveChats
import dev.telegrammcp.server.util.StructuredLogger
import org.springframework.stereotype.Service
import java.util.regex.Pattern

/**
 * Validates tool inputs against security guardrails.
 *
 * Checks include:
 * - **Optional input denylist** — blocks inputs matching operator-defined patterns.
 * - **Input length limits** — prevents excessively large payloads.
 * - **Chat access rules** — intersect allow-lists and grants, then apply the operator's deny-list.
 * - **Sensitive chats** — known credential and account-control chats require explicit opt-in.
 */
@Service
class GuardrailService(
    private val mcpProps: McpSecurityProperties,
    private val telegramProps: TelegramProperties,
    private val permissions: dev.telegrammcp.server.security.AccessPermissionService? = null,
) {

    private val log = StructuredLogger.forClass<GuardrailService>()
    private val blockedChats = telegramProps.security.blockedChatIds.toSet()

    /** Compiled patterns — built once at startup. */
    private val blockedPatterns: List<Pattern> =
        mcpProps.guardrails.blockedPatterns.map { Pattern.compile(it) }

    /**
     * Validates that [input] does not exceed the max length and does not
     * match any operator-defined blocked pattern.
     *
     * @throws InvalidToolInputException if the input is too long
     * @throws GuardrailViolationException if a blocked pattern matches
     */
    fun validateInput(input: String) {
        val maxLen = mcpProps.guardrails.maxToolInputLength
        if (input.length > maxLen) {
            throw InvalidToolInputException(
                "Input exceeds maximum allowed length ($maxLen characters)",
            )
        }

        blockedPatterns.forEach { pattern ->
            if (pattern.matcher(input).find()) {
                log.warn("Blocked input pattern '{}' matched tool input", pattern.pattern())
                throw GuardrailViolationException(
                    "Input rejected by the configured input filter",
                )
            }
        }
    }

    /**
     * Ensures the [chatId] is permitted by the current chat policy.
     * @throws ChatNotAllowedException if access to the chat is denied
     */
    fun validateChatAccess(chatId: Long) = enforceChatAccess(chatId, callerChatId = chatId)

    /** Validates a chat learned from Telegram without returning a rejected ID to the caller. */
    fun validateDerivedChatAccess(chatId: Long) = enforceChatAccess(chatId, callerChatId = null)

    private fun enforceChatAccess(chatId: Long, callerChatId: Long?) {
        if (chatId in blockedChats) {
            log.warn("Chat {} is blocked by the configured chat deny-list", chatId)
            throw ChatNotAllowedException("Chat access denied by the server's chat policy")
        }
        if (isSensitiveChatBlocked(chatId)) {
            log.warn("Chat {} is a sensitive chat and no opt-in allows it", chatId)
            throw SensitiveChatBlockedException(callerChatId)
        }
        if (!isWithinReadScope(chatId) || mutationDenied(chatId)) {
            log.warn("Chat {} is outside the current chat scope", chatId)
            throw callerChatId?.let(::ChatNotAllowedException) ?: ChatNotAllowedException()
        }
    }

    /**
     * Activates the mutation chat list for a write tool. Read tools get a no-op
     * close, so an empty mutation list still means the same chats as reads.
     */
    fun mutationScope(toolName: String): AutoCloseable {
        if (toolName !in OperationGuardService.WRITE_TOOLS) return AutoCloseable { }
        val previous = mutationScope.get()
        mutationScope.set(true)
        return AutoCloseable {
            if (previous == null) mutationScope.remove() else mutationScope.set(previous)
        }
    }

    /**
     * Read access check for filtering chat-scoped results.
     */
    fun isChatAllowed(chatId: Long): Boolean =
        chatId !in blockedChats && isWithinReadScope(chatId) && !isSensitiveChatBlocked(chatId)

    private fun isWithinReadScope(chatId: Long): Boolean {
        val allowed = telegramProps.security.allowedChatIds
        val scoped = permissions?.requestChatIds()
        return (allowed.isEmpty() || chatId in allowed) && (scoped == null || chatId in scoped)
    }

    /**
     * True when [chatId] is a [SensitiveChats] entry and nothing opts in to it.
     * Opt-ins: the global `allow-sensitive-chats` flag, the active grant's
     * `allow_sensitive_chats`, or the chat ID named explicitly in the static
     * allow-list or the active grant's `chat_ids`. An all-chats grant
     * (`chat_ids: null`) or an empty allow-list never counts as naming it.
     */
    fun isSensitiveChatBlocked(chatId: Long): Boolean {
        if (!SensitiveChats.isSensitive(chatId)) return false
        val security = telegramProps.security
        if (security.allowSensitiveChats || chatId in security.allowedChatIds) return false
        return permissions?.requestAllowsSensitiveChat(chatId) != true
    }

    /** Includes the active write scope so dynamic-target operations fail closed. */
    fun hasRestrictedChatScope(): Boolean = blockedChats.isNotEmpty() || telegramProps.security.allowedChatIds.isNotEmpty() ||
        permissions?.requestChatIds() != null ||
        (mutationScope.get() == true && telegramProps.security.mutationAllowedChatIds.isNotEmpty())

    private val mutationScope = ThreadLocal<Boolean>()

    /** True when a write is running and [chatId] is outside the mutation list. */
    private fun mutationDenied(chatId: Long): Boolean {
        if (mutationScope.get() != true) return false
        val mutation = telegramProps.security.mutationAllowedChatIds
        return mutation.isNotEmpty() && chatId !in mutation
    }

    /**
     * Operations whose target chat cannot be known before a side effect must
     * fail closed while an explicit chat restriction is configured.
     */
    fun requireUnrestrictedChatScope(operation: String) {
        val mutationList = telegramProps.security.mutationAllowedChatIds.isNotEmpty()
        if (hasRestrictedChatScope() || mutationList) {
            log.warn("Blocked '{}' because its target cannot be pre-validated against the chat policy", operation)
            throw GuardrailViolationException(
                "Operation '$operation' requires a target the server can validate against the chat policy",
            )
        }
    }
}
