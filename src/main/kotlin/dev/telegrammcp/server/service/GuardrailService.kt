package dev.telegrammcp.server.service

import dev.telegrammcp.server.config.McpSecurityProperties
import dev.telegrammcp.server.config.TelegramProperties
import dev.telegrammcp.server.exception.ChatNotAllowedException
import dev.telegrammcp.server.exception.GuardrailViolationException
import dev.telegrammcp.server.exception.InvalidToolInputException
import dev.telegrammcp.server.util.StructuredLogger
import org.springframework.stereotype.Service
import java.util.regex.Pattern

/**
 * Validates tool inputs against security guardrails.
 *
 * Checks include:
 * - **Optional input denylist** — blocks inputs matching operator-defined patterns.
 * - **Input length limits** — prevents excessively large payloads.
 * - **Chat allow-list** — restricts which Telegram chats can be accessed.
 */
@Service
class GuardrailService(
    private val mcpProps: McpSecurityProperties,
    private val telegramProps: TelegramProperties,
    private val permissions: dev.telegrammcp.server.security.AccessPermissionService? = null,
) {

    private val log = StructuredLogger.forClass<GuardrailService>()

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
     * Ensures the [chatId] is permitted by the allow-list.
     *
     * When the allow-list is empty every chat is allowed (convenient for dev).
     *
     * @throws ChatNotAllowedException if the chat is explicitly not allowed
     */
    fun validateChatAccess(chatId: Long) {
        if (!isChatAllowed(chatId) || mutationDenied(chatId)) {
            log.warn("Chat {} is not in the allow-list", chatId)
            throw ChatNotAllowedException(chatId)
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
     * Non-throwing allow-list check for filtering result lists, where a chat
     * outside the allow-list should be omitted rather than fail the call.
     */
    fun isChatAllowed(chatId: Long): Boolean {
        val allowed = telegramProps.security.allowedChatIds
        val scoped = permissions?.requestChatIds()
        return (allowed.isEmpty() || chatId in allowed) && (scoped == null || chatId in scoped)
    }

    /** True when the operator restricted the connector to explicit chat IDs. */
    fun hasChatAllowList(): Boolean = telegramProps.security.allowedChatIds.isNotEmpty() || permissions?.requestChatIds() != null

    /**
     * Validates a chat ID learned from Telegram without echoing the rejected ID
     * back to the caller.
     */
    fun validateDerivedChatAccess(chatId: Long) {
        if (!isChatAllowed(chatId) || mutationDenied(chatId)) {
            log.warn("Resolved chat {} is not in the allow-list", chatId)
            throw ChatNotAllowedException()
        }
    }

    private val mutationScope = ThreadLocal<Boolean>()

    /** True when a write is running and [chatId] is outside the mutation list. */
    private fun mutationDenied(chatId: Long): Boolean {
        if (mutationScope.get() != true) return false
        val mutation = telegramProps.security.mutationAllowedChatIds
        return mutation.isNotEmpty() && chatId !in mutation
    }

    /**
     * Operations whose target chat cannot be known before a side effect must
     * fail closed while a static allow-list is configured.
     */
    fun requireUnrestrictedChatScope(operation: String) {
        val mutationList = telegramProps.security.mutationAllowedChatIds.isNotEmpty()
        if (hasChatAllowList() || mutationList) {
            log.warn("Blocked '{}' because its target cannot be pre-validated against the chat allow-list", operation)
            throw GuardrailViolationException(
                "Operation '$operation' is unavailable while a static chat allow-list is configured",
            )
        }
    }
}
