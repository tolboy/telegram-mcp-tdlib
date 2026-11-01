package dev.telegrammcp.server.security

import dev.telegrammcp.server.config.ServerModeProperties
import dev.telegrammcp.server.config.TelegramProperties
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Writes without `TELEGRAM_MUTATION_CHAT_IDS` follow the read allow-list.
 * That list is every chat when it is empty. Say so at startup instead of
 * changing that default under existing deployments.
 */
@Component
class MutationChatListAdvisor(
    private val serverMode: ServerModeProperties,
    private val telegram: TelegramProperties,
) {
    private val log = LoggerFactory.getLogger(MutationChatListAdvisor::class.java)

    @PostConstruct
    fun advise() {
        if (serverMode.readOnly || telegram.security.mutationAllowedChatIds.isNotEmpty()) return
        log.warn(
            "Writes are enabled and TELEGRAM_MUTATION_CHAT_IDS is empty. " +
                "Write tools may change every chat the read allow-list already permits. " +
                "Set TELEGRAM_MUTATION_CHAT_IDS to restrict those chats.",
        )
    }
}
