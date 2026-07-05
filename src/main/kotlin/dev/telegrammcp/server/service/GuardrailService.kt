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
        val allowed = telegramProps.security.allowedChatIds
        if (allowed.isNotEmpty() && chatId !in allowed) {
            log.warn("Chat {} is not in the allow-list", chatId)
            throw ChatNotAllowedException(chatId)
        }
    }

    /**
     * Non-throwing allow-list check for filtering result lists, where a chat
     * outside the allow-list should be omitted rather than fail the call.
     */
    fun isChatAllowed(chatId: Long): Boolean {
        val allowed = telegramProps.security.allowedChatIds
        return allowed.isEmpty() || chatId in allowed
    }
}
