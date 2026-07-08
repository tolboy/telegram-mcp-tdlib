package dev.telegrammcp.server.service

import dev.telegrammcp.server.config.ServerModeProperties
import dev.telegrammcp.server.client.TelegramAccountContext
import dev.telegrammcp.server.model.AuditCategory
import dev.telegrammcp.server.model.AuditEntry
import dev.telegrammcp.server.model.AuditOutcome
import dev.telegrammcp.server.util.StructuredLogger
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.MDC
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Audit logging service for all MCP tool invocations.
 *
 * Every tool call is recorded with:
 * - **What** — tool name, category, arguments (if configured)
 * - **Who** — traceId, sessionId from MDC
 * - **When** — timestamp and execution duration
 * - **Result** — success, error, or blocked by policy
 *
 * Audit entries are:
 * 1. Logged via the structured logger (appears in both console and JSON output)
 * 2. Stored in a bounded in-memory ring buffer (last 1000 entries)
 * 3. Counted via Micrometer metrics (`mcp.audit.operations` counter)
 */
@Service
class AuditService(
    private val props: ServerModeProperties,
    private val meterRegistry: MeterRegistry,
    private val accountContext: TelegramAccountContext? = null,
) {

    private val log = StructuredLogger.forClass<AuditService>()

    /** In-memory ring buffer of recent audit entries (bounded to 1000). */
    private val recentEntries = ConcurrentLinkedDeque<AuditEntry>()

    companion object {
        private const val MAX_ENTRIES = 1000
        private const val REDACTED = "***REDACTED***"
        private val SENSITIVE_KEY_TOKENS = listOf("password", "secret", "token", "api_key", "apikey", "phone")

        /** Maps tool names to audit categories. */
        private val TOOL_CATEGORIES = mapOf(
            // Read messages
            "get_history" to AuditCategory.READ_MESSAGE,
            "get_messages" to AuditCategory.READ_MESSAGE,
            "search_messages" to AuditCategory.READ_MESSAGE,
            "search_global" to AuditCategory.READ_MESSAGE,
            "get_pinned_messages" to AuditCategory.READ_MESSAGE,

            // Send/edit messages
            "send_message" to AuditCategory.SEND_MESSAGE,
            "reply_to_message" to AuditCategory.SEND_MESSAGE,
            "forward_message" to AuditCategory.SEND_MESSAGE,
            "edit_message" to AuditCategory.EDIT_MESSAGE,
            "delete_message" to AuditCategory.EDIT_MESSAGE,
            "pin_message" to AuditCategory.EDIT_MESSAGE,
            "unpin_message" to AuditCategory.EDIT_MESSAGE,
            "mark_as_read" to AuditCategory.EDIT_MESSAGE,
            "list_scheduled_messages" to AuditCategory.READ_MESSAGE,
            "schedule_message" to AuditCategory.SEND_MESSAGE,
            "reschedule_message" to AuditCategory.EDIT_MESSAGE,
            "cancel_scheduled_message" to AuditCategory.EDIT_MESSAGE,

            // Read chats
            "list_chats" to AuditCategory.READ_CHAT,
            "get_chat" to AuditCategory.READ_CHAT,
            "get_participants" to AuditCategory.READ_CHAT,

            // Manage chats
            "create_group" to AuditCategory.MANAGE_CHAT,
            "invite_to_group" to AuditCategory.MANAGE_CHAT,
            "leave_chat" to AuditCategory.MANAGE_CHAT,
            "ban_user" to AuditCategory.MANAGE_CHAT,
            "unban_user" to AuditCategory.MANAGE_CHAT,
            "promote_admin" to AuditCategory.MANAGE_CHAT,
            "demote_admin" to AuditCategory.MANAGE_CHAT,
            "edit_chat_title" to AuditCategory.MANAGE_CHAT,
            "archive_chat" to AuditCategory.MANAGE_CHAT,
            "unarchive_chat" to AuditCategory.MANAGE_CHAT,
            "mute_chat" to AuditCategory.MANAGE_CHAT,
            "unmute_chat" to AuditCategory.MANAGE_CHAT,
            "list_chat_folders" to AuditCategory.READ_CHAT,
            "get_chat_folder" to AuditCategory.READ_CHAT,
            "configure_chat_folder" to AuditCategory.MANAGE_CHAT,
            "delete_chat_folder" to AuditCategory.MANAGE_CHAT,
            "get_group_permissions" to AuditCategory.READ_CHAT,
            "set_group_permissions" to AuditCategory.MANAGE_CHAT,
            "set_member_permissions" to AuditCategory.MANAGE_CHAT,
            "set_admin_rights" to AuditCategory.MANAGE_CHAT,

            // Account privacy and bot command menus
            "get_privacy_settings" to AuditCategory.PROFILE,
            "set_privacy_settings" to AuditCategory.PROFILE,
            "get_bot_commands" to AuditCategory.MANAGE_CHAT,
            "set_bot_commands" to AuditCategory.MANAGE_CHAT,

            // Read contacts/users
            "list_contacts" to AuditCategory.READ_CONTACT,
            "get_me" to AuditCategory.READ_USER,
            "resolve_username" to AuditCategory.ENTITY_RESOLUTION,

            // Manage contacts
            "add_contact" to AuditCategory.MANAGE_CONTACT,
            "delete_contact" to AuditCategory.MANAGE_CONTACT,
            "block_user" to AuditCategory.MANAGE_CONTACT,
            "unblock_user" to AuditCategory.MANAGE_CONTACT,

            // Media
            "download_media" to AuditCategory.MEDIA_DOWNLOAD,
            "get_media_info" to AuditCategory.MEDIA_DOWNLOAD,
            "send_file" to AuditCategory.MEDIA_UPLOAD,

            // Reactions & polls
            "send_reaction" to AuditCategory.REACT_MESSAGE,
            "remove_reaction" to AuditCategory.REACT_MESSAGE,
            "get_message_reactions" to AuditCategory.REACT_MESSAGE,
            "create_poll" to AuditCategory.POLL,
            "get_message_context" to AuditCategory.READ_MESSAGE,

            // Inline buttons & forum topics
            "list_inline_buttons" to AuditCategory.INLINE_INTERACTION,
            "press_inline_button" to AuditCategory.INLINE_INTERACTION,
            "list_topics" to AuditCategory.FORUM_TOPIC,

            // Channels & invite links
            "create_channel" to AuditCategory.MANAGE_CHAT,
            "create_supergroup" to AuditCategory.MANAGE_CHAT,
            "get_invite_link" to AuditCategory.MANAGE_CHAT,
            "join_chat_by_link" to AuditCategory.MANAGE_CHAT,
            "subscribe_public_channel" to AuditCategory.MANAGE_CHAT,
            "register_internal_chat" to AuditCategory.MANAGE_CHAT,
            "get_admins" to AuditCategory.READ_CHAT,
            "get_banned_users" to AuditCategory.READ_CHAT,

            // Voice, stickers & chat photo
            "send_voice" to AuditCategory.VOICE,
            "transcribe_voice_note" to AuditCategory.VOICE,
            "send_sticker" to AuditCategory.STICKER,
            "get_sticker_sets" to AuditCategory.STICKER,
            "edit_chat_photo" to AuditCategory.MANAGE_CHAT,
            "delete_chat_photo" to AuditCategory.MANAGE_CHAT,

            // Drafts & profile
            "save_draft" to AuditCategory.DRAFT,
            "get_drafts" to AuditCategory.DRAFT,
            "clear_draft" to AuditCategory.DRAFT,
            "update_profile" to AuditCategory.PROFILE,
            "set_profile_photo" to AuditCategory.PROFILE,
            "delete_profile_photo" to AuditCategory.PROFILE,
            "get_user_photos" to AuditCategory.READ_USER,

            // Search, contacts & admin
            "search_public_chats" to AuditCategory.READ_CHAT,
            "search_contacts" to AuditCategory.READ_CONTACT,
            "get_blocked_users" to AuditCategory.READ_USER,
            "get_user_status" to AuditCategory.READ_USER,
            "get_recent_actions" to AuditCategory.ADMIN_LOG,

            // Utilities
            "message_from_link" to AuditCategory.READ_MESSAGE,
            "get_last_interaction" to AuditCategory.READ_MESSAGE,

            // Public-chat search — read-only discovery
            "discover_public_chats" to AuditCategory.PUBLIC_SEARCH_READ,
            "search_public_messages" to AuditCategory.PUBLIC_SEARCH_READ,
        )
    }

    /**
     * Records a tool invocation in the audit log.
     *
     * @param toolName   MCP tool name
     * @param arguments  tool input arguments (may be redacted based on config)
     * @param outcome    result of the invocation
     * @param error      error message if outcome is ERROR
     * @param durationMs execution time in milliseconds
     */
    fun record(
        toolName: String,
        arguments: Map<String, Any> = emptyMap(),
        outcome: AuditOutcome = AuditOutcome.SUCCESS,
        error: String? = null,
        durationMs: Long? = null,
    ) {
        if (!props.audit.enabled) return

        val category = TOOL_CATEGORIES[toolName] ?: AuditCategory.READ_MESSAGE

        val sanitizedArgs = if (props.audit.logArguments) {
            sanitizeArguments(arguments)
        } else {
            // Only include non-sensitive keys
            arguments.filterKeys { it in setOf("chat_id", "limit", "offset", "query") }
        }

        val entry = AuditEntry(
            timestamp = Instant.now(),
            toolName = toolName,
            category = category,
            arguments = sanitizedArgs,
            traceId = MDC.get("traceId"),
            sessionId = MDC.get("sessionId"),
            account = runCatching { accountContext?.currentAccount() }.getOrNull(),
            outcome = outcome,
            errorMessage = error,
            durationMs = durationMs,
        )

        // Log the entry
        when (outcome) {
            AuditOutcome.SUCCESS -> log.info(
                "AUDIT [{}] {} — {} ({}ms)",
                category, toolName, outcome, durationMs ?: 0,
            )
            AuditOutcome.ERROR -> log.warn(
                "AUDIT [{}] {} — {} — {}",
                category, toolName, outcome, error,
            )
            AuditOutcome.BLOCKED_READONLY,
            AuditOutcome.BLOCKED_CONFIRMATION,
            AuditOutcome.BLOCKED_GUARDRAIL,
            AuditOutcome.BLOCKED_ANTISPAM,
            -> log.warn(
                "AUDIT [{}] {} — {} — blocked by policy{}",
                category, toolName, outcome,
                error?.let { ": $it" } ?: "",
            )
        }

        // Store in ring buffer
        recentEntries.addFirst(entry)
        while (recentEntries.size > MAX_ENTRIES) {
            recentEntries.removeLast()
        }

        // Increment metrics counter
        meterRegistry.counter(
            "mcp.audit.operations",
            "tool", toolName,
            "category", category.name,
            "outcome", outcome.name,
        ).increment()
    }

    /**
     * Returns the most recent audit entries (newest first).
     *
     * @param limit max entries to return
     */
    fun getRecentEntries(limit: Int = 100): List<AuditEntry> {
        return recentEntries.take(limit)
    }

    /**
     * Removes sensitive values from arguments before logging. Matching is
     * token-based ("bot_token", "proxyPassword") and recursive so credentials
     * nested inside object or array arguments cannot slip into the audit log.
     */
    private fun sanitizeArguments(args: Map<String, Any>): Map<String, Any> =
        args.mapValues { (key, value) -> sanitizeValue(key, value) }

    private fun sanitizeValue(key: String, value: Any?): Any = when {
        isSensitiveKey(key) -> REDACTED
        value is Map<*, *> -> value.entries.associate { (nestedKey, nestedValue) ->
            val name = nestedKey.toString()
            name to sanitizeValue(name, nestedValue)
        }
        value is List<*> -> value.map { element -> sanitizeValue(key = "", value = element) }
        else -> value ?: ""
    }

    private fun isSensitiveKey(key: String): Boolean {
        val normalized = key.lowercase()
        return SENSITIVE_KEY_TOKENS.any { it in normalized }
    }
}
