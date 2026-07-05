package dev.telegrammcp.server.service

import dev.telegrammcp.server.config.McpSecurityProperties
import dev.telegrammcp.server.config.McpToolProfile
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

    val profile: McpToolProfile
        get() = properties.toolProfile

    fun isVisible(toolName: String, readOnly: Boolean): Boolean =
        allowedByProfile(toolName) &&
            allowedByExactNames(toolName) &&
            (!readOnly || toolName !in OperationGuardService.WRITE_TOOLS)

    fun visibleToolNames(toolNames: Collection<String>, readOnly: Boolean): List<String> =
        toolNames.filter { isVisible(it, readOnly) }

    fun validateConfiguredNames(registeredToolNames: Collection<String>) {
        val registered = registeredToolNames.toSet()
        val configured = normalizedAllow + normalizedDeny
        val unknown = configured - registered
        require(unknown.isEmpty()) {
            "Unknown MCP tool name(s) in MCP_TOOL_ALLOW/MCP_TOOL_DENY: ${unknown.sorted().joinToString()}"
        }
    }

    private val normalizedAllow: Set<String> =
        properties.toolAllow.map(String::trim).filter(String::isNotEmpty).toSet()

    private val normalizedDeny: Set<String> =
        properties.toolDeny.map(String::trim).filter(String::isNotEmpty).toSet()

    private fun allowedByExactNames(toolName: String): Boolean =
        (normalizedAllow.isEmpty() || toolName in normalizedAllow) && toolName !in normalizedDeny

    private fun allowedByProfile(toolName: String): Boolean = when (profile) {
        McpToolProfile.ALL -> true
        McpToolProfile.READER -> toolName !in OperationGuardService.WRITE_TOOLS
        McpToolProfile.INBOX -> toolName in INBOX_TOOL_NAMES
        McpToolProfile.COMMUNITY_ADMIN -> toolName in COMMUNITY_ADMIN_TOOL_NAMES
        McpToolProfile.RESEARCH ->
            toolName in RESEARCH_TOOL_NAMES && toolName !in OperationGuardService.WRITE_TOOLS
    }

    companion object {
        private val META_TOOLS = setOf("_manifest", "list_accounts")

        private val MESSAGE_TOOLS = setOf(
            "get_history", "get_messages", "search_messages", "search_global",
            "send_message", "reply_to_message", "edit_message", "delete_message",
            "forward_message", "pin_message", "unpin_message", "get_pinned_messages",
            "mark_as_read", "send_reaction", "remove_reaction", "get_message_reactions",
            "create_poll", "vote_poll", "close_poll", "get_message_viewers",
            "get_message_context", "list_inline_buttons", "press_inline_button",
            "message_from_link", "get_message_link", "list_scheduled_messages", "schedule_message",
            "reschedule_message", "cancel_scheduled_message",
        )

        private val MEDIA_TOOLS = setOf(
            "get_media_info", "download_media", "send_file", "send_voice",
            "transcribe_voice_note", "send_sticker", "get_sticker_sets",
        )

        private val INBOX_TOOL_NAMES = META_TOOLS + MESSAGE_TOOLS + MEDIA_TOOLS + setOf(
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
            "subscribe_public_channel", "get_bot_commands", "set_bot_commands", "resolve_username", "get_me",
        )

        private val RESEARCH_TOOL_NAMES = META_TOOLS + setOf(
            "get_history", "get_messages", "search_messages", "search_global", "get_message_context",
            "get_pinned_messages", "get_message_reactions", "message_from_link", "get_message_link", "get_media_info",
            "download_media", "list_chats", "get_chat", "get_participants", "get_admins",
            "get_banned_users", "get_recent_actions", "search_public_chats", "discover_public_chats",
            "search_public_messages", "export_chat_history",
            "resolve_username", "get_user_status", "get_user_photos",
        )
    }
}
