package dev.telegrammcp.server.service

import dev.telegrammcp.server.config.McpSecurityProperties
import dev.telegrammcp.server.config.McpToolProfile
import dev.telegrammcp.server.util.StructuredLogger
import org.springframework.stereotype.Service

/**
 * Selects a deliberately small MCP surface for a user job before tools are
 * advertised. It composes with read-only mode: a profile can narrow the
 * surface, but can never re-expose a write tool that server policy hides.
 */
@Service
class ToolSurfacePolicy(
    private val properties: McpSecurityProperties,
) {

    private val log = StructuredLogger.forClass<ToolSurfacePolicy>()

    val profile: McpToolProfile
        get() = properties.toolProfile

    /** Parsed `MCP_TOOL_ALLOW`; empty means every profile tool. Invalid patterns fail here, at startup. */
    private val allowPatterns: List<ToolNamePattern> = ToolNamePattern.parseAll(properties.toolAllow, "MCP_TOOL_ALLOW")

    /** Parsed `MCP_TOOL_DENY`; a bare `*` is refused because it would hide every tool. */
    private val denyPatterns: List<ToolNamePattern> = ToolNamePattern.parseAll(properties.toolDeny, "MCP_TOOL_DENY").also { deny ->
        require(deny.none { it.matchesEverything }) {
            "MCP_TOOL_DENY must not contain a bare '*': it would hide every tool. Narrow MCP_TOOL_PROFILE or MCP_TOOL_ALLOW instead."
        }
    }

    private val allowEverything: Boolean = allowPatterns.any { it.matchesEverything }

    fun isVisible(toolName: String, readOnly: Boolean, allowDownloads: Boolean = false): Boolean =
        allowedByProfile(toolName, allowDownloads) &&
            allowedByNameFilters(toolName) &&
            !OperationGuardService.blockedByReadOnly(toolName, readOnly, allowDownloads)

    fun visibleToolNames(toolNames: Collection<String>, readOnly: Boolean, allowDownloads: Boolean = false): List<String> =
        toolNames.filter { isVisible(it, readOnly, allowDownloads) }

    /**
     * Checks the allow and deny lists against the registered tools at startup.
     *
     * Every entry that matches no registered tool is logged as a warning, and
     * every such entry that would fail open stops startup: an unknown exact
     * name in either list, and an unmatched `prefix*` in the deny list (a typo
     * there leaves tools visible that were meant to be hidden). An unmatched
     * `prefix*` in the allow list only warns, because it fails safe: it hides
     * tools rather than exposing them.
     */
    fun validateConfiguredNames(registeredToolNames: Collection<String>) {
        val registered = registeredToolNames.toSet()
        if (allowEverything) {
            log.info("MCP_TOOL_ALLOW contains '*': every tool of profile {} stays allowed (deny list and read-only still apply)", profile)
        }
        val unknownExact = mutableListOf<String>()
        val unmatchedDenyPatterns = mutableListOf<String>()
        for ((setting, patterns) in listOf("MCP_TOOL_ALLOW" to allowPatterns, "MCP_TOOL_DENY" to denyPatterns)) {
            for (pattern in patterns) {
                if (pattern.matchesEverything || registered.any(pattern::matches)) continue
                log.warn("{} entry '{}' matches no registered tool", setting, pattern.raw)
                when {
                    !pattern.isWildcard -> unknownExact += pattern.raw
                    setting == "MCP_TOOL_DENY" -> unmatchedDenyPatterns += pattern.raw
                }
            }
        }
        val problems = buildList {
            if (unknownExact.isNotEmpty()) {
                add("Unknown MCP tool name(s) in MCP_TOOL_ALLOW/MCP_TOOL_DENY: ${unknownExact.distinct().sorted().joinToString()}")
            }
            if (unmatchedDenyPatterns.isNotEmpty()) {
                add("MCP_TOOL_DENY pattern(s) match no registered tool, so they would block nothing: " +
                    unmatchedDenyPatterns.distinct().sorted().joinToString())
            }
        }
        require(problems.isEmpty()) { problems.joinToString("; ") }
    }

    /** Deny wins: a tool is hidden when any deny entry matches it, whatever the allow list says. */
    private fun allowedByNameFilters(toolName: String): Boolean =
        (allowPatterns.isEmpty() || allowPatterns.any { it.matches(toolName) }) &&
            denyPatterns.none { it.matches(toolName) }

    private fun allowedByProfile(toolName: String, allowDownloads: Boolean): Boolean = when (profile) {
        McpToolProfile.ALL -> true
        McpToolProfile.READER -> !OperationGuardService.blockedByReadOnly(toolName, true, allowDownloads)
        McpToolProfile.INBOX -> toolName in INBOX_TOOL_NAMES
        McpToolProfile.COMMUNITY_ADMIN -> toolName in COMMUNITY_ADMIN_TOOL_NAMES
        McpToolProfile.RESEARCH ->
            toolName in RESEARCH_TOOL_NAMES && !OperationGuardService.blockedByReadOnly(toolName, true, allowDownloads)
    }

    companion object {
        private val META_TOOLS = setOf("_manifest", "list_accounts")

        private val MESSAGE_TOOLS = setOf(
            "inbox_snapshot", "conversation_bundle", "changes_since", "wait_for_changes", "get_send_operation",
            "get_history", "get_messages", "search_messages", "search_global",
            "send_message", "reply_to_message", "edit_message", "delete_message",
            "forward_message", "pin_message", "unpin_message", "get_pinned_messages",
            "mark_as_read", "send_reaction", "remove_reaction", "get_message_reactions",
            "create_poll", "vote_poll", "close_poll", "get_message_viewers",
            "get_message_context", "list_inline_buttons", "press_inline_button",
            "message_from_link", "get_message_link", "list_scheduled_messages", "schedule_message",
            "reschedule_message", "cancel_scheduled_message",
            "unpin_all_messages", "send_chat_action", "send_location", "send_venue", "send_contact",
        )

        private val MEDIA_TOOLS = setOf(
            "get_media_info", "download_media", "send_file", "send_voice",
            "transcribe_voice_note", "send_sticker", "get_sticker_sets", "send_album",
        )

        private val INBOX_TOOL_NAMES = META_TOOLS + MESSAGE_TOOLS + MEDIA_TOOLS + setOf(
            "list_chats", "get_chat", "list_chat_folders", "get_chat_folder",
            "get_me", "list_contacts", "search_contacts", "resolve_username", "get_user_status",
            "get_user_photos", "get_last_interaction", "add_contact", "delete_contact",
            "block_user", "unblock_user", "get_blocked_users", "update_profile",
            "set_profile_photo", "delete_profile_photo", "save_draft", "get_drafts", "clear_draft",
            "get_privacy_settings", "set_privacy_settings", "get_common_chats",
        )

        private val COMMUNITY_ADMIN_TOOL_NAMES = META_TOOLS + MESSAGE_TOOLS + MEDIA_TOOLS + setOf(
            "list_chats", "get_chat", "get_participants", "get_admins", "get_banned_users",
            "get_recent_actions", "create_group", "create_channel", "create_supergroup",
            "invite_to_group", "leave_chat", "ban_user", "unban_user", "promote_admin",
            "demote_admin", "edit_chat_title", "edit_chat_photo", "delete_chat_photo",
            "set_chat_description", "set_slow_mode",
            "get_group_permissions", "set_group_permissions", "set_member_permissions", "set_admin_rights",
            "list_topics", "create_topic", "edit_forum_topic", "close_forum_topic",
            "reopen_forum_topic", "set_forum_topics_enabled", "get_invite_link", "join_chat_by_link",
            "list_invite_links", "revoke_invite_link",
            "list_join_requests", "process_join_request", "delete_messages_by_sender", "get_chat_statistics",
            "subscribe_public_channel", "get_bot_commands", "set_bot_commands", "resolve_username", "get_me",
        )

        private val RESEARCH_TOOL_NAMES = META_TOOLS + setOf(
            "get_history", "get_messages", "search_messages", "search_global", "get_message_context",
            "get_pinned_messages", "get_message_reactions", "message_from_link", "get_message_link", "get_media_info",
            "download_media", "list_chats", "get_chat", "get_participants", "get_admins",
            "get_banned_users", "get_recent_actions", "search_public_chats", "discover_public_chats",
            "search_public_messages", "export_chat_history", "export_job", "conversation_bundle", "changes_since", "wait_for_changes",
            "resolve_username", "get_user_status", "get_user_photos",
            "list_join_requests", "get_chat_statistics",
        )
    }
}

/**
 * One entry of `MCP_TOOL_ALLOW` / `MCP_TOOL_DENY`: an exact tool name, a
 * `prefix*` that matches every tool name starting with `prefix`, or a bare
 * `*` (allow list only) that matches every tool. Only a single trailing `*`
 * is supported; anything else fails startup.
 */
class ToolNamePattern private constructor(val raw: String, private val prefix: String, val isWildcard: Boolean) {
    val matchesEverything: Boolean get() = isWildcard && prefix.isEmpty()

    fun matches(toolName: String): Boolean = if (isWildcard) toolName.startsWith(prefix) else toolName == prefix

    override fun toString(): String = raw

    companion object {
        private val NAME = Regex("[A-Za-z0-9_]*")

        fun parse(entry: String, setting: String): ToolNamePattern {
            val raw = entry.trim()
            val wildcard = raw.endsWith("*")
            val body = if (wildcard) raw.dropLast(1) else raw
            require(raw.isNotEmpty() && '*' !in body && NAME.matches(body)) {
                "Invalid $setting entry '$raw': use an exact tool name, 'prefix*' with a single trailing '*', or '*'"
            }
            return ToolNamePattern(raw, body, wildcard)
        }

        fun parseAll(entries: List<String>, setting: String): List<ToolNamePattern> =
            entries.map(String::trim).filter(String::isNotEmpty).distinct().map { parse(it, setting) }
    }
}
