package dev.telegrammcp.server.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Safety and throughput limits for read-only public-chat search tools.
 *
 * This is deliberately transport- and language-neutral. An MCP client or its
 * orchestrator supplies any search variants it needs; the server never embeds
 * product-specific lead scoring or a vocabulary for one locale.
 */
@ConfigurationProperties(prefix = "public-search")
data class PublicSearchProperties(
    /** Master switch. When false, public-search tools refuse to run. */
    val enabled: Boolean = true,

    /**
     * Optional usernames or numeric chat IDs allowed to appear in public-chat
     * discovery results. Keep this non-empty for smoke tests against a known
     * safe set of chats; an empty value permits ordinary account-scoped search.
     */
    val chatAllowlist: List<String> = emptyList(),

    /** Hard caps that apply regardless of MCP client input. */
    val limits: LimitsProps = LimitsProps(),

    /** Bounded coroutine fan-out for cross-chat search. */
    val fanout: FanoutProps = FanoutProps(),

) {
    data class LimitsProps(
        /** Max public chats returned or scanned by one tool call. */
        val maxChatsPerSearch: Int = 20,
        /** Max messages searched in one chat by one tool call. */
        val maxMessagesPerChat: Int = 60,
    )

    data class FanoutProps(
        /** Max chats searched in parallel. */
        val maxConcurrentChats: Int = 5,
        /** Max query variants searched in parallel in a single chat. */
        val maxConcurrentQueriesPerChat: Int = 3,
        /** Wall-clock cap (ms) on a complete cross-chat tool call. */
        val toolCallTimeoutMs: Long = 25_000,
    )
}
