package dev.telegrammcp.server.service

import dev.telegrammcp.server.config.ServerModeProperties
import dev.telegrammcp.server.exception.ConfirmationRequiredException
import dev.telegrammcp.server.exception.ReadOnlyModeException
import dev.telegrammcp.server.util.StructuredLogger
import io.modelcontextprotocol.spec.McpSchema
import org.springframework.stereotype.Service

/**
 * Guards tool execution based on server operational policies:
 *
 * 1. **Read-only mode** — blocks all write/mutating tools
 * 2. **Confirmation mode** — requires explicit `"confirmed": true` argument
 *    for destructive operations (delete, ban, leave, etc.)
 *
 * This service is called at the start of every tool execution before any
 * Telegram API call is made.
 */
@Service
class OperationGuardService(
    private val props: ServerModeProperties,
    private val antiSpamGuardService: AntiSpamGuardService,
) {

    private val log = StructuredLogger.forClass<OperationGuardService>()

    companion object {
        /** Tools that modify state (messages, chats, contacts, files). */
        val WRITE_TOOLS = setOf(
            // Message write operations
            "send_message", "reply_to_message", "edit_message", "delete_message",
            "forward_message", "pin_message", "unpin_message",

            // Chat management
            "create_group", "create_channel", "create_supergroup",
            "create_topic", "edit_forum_topic", "close_forum_topic", "reopen_forum_topic",
            "set_forum_topics_enabled",
            "invite_to_group", "leave_chat",
            "edit_chat_title", "edit_chat_photo", "delete_chat_photo",
            "set_chat_description", "set_slow_mode",
            "promote_admin", "demote_admin", "ban_user", "unban_user",

            // Contact management
            "add_contact", "delete_contact", "block_user", "unblock_user",

            // Local file writes and media upload
            "download_media", "send_file", "send_voice", "send_sticker",

            // Telegram's native transcription may consume Premium/trial quota.
            "transcribe_voice_note",

            // Profile
            "update_profile", "set_profile_photo", "delete_profile_photo",

            // Drafts
            "save_draft", "clear_draft",

            // Chat settings
            "archive_chat", "unarchive_chat", "mute_chat", "unmute_chat",

            // Message operations
            "mark_as_read",

            // Reactions & polls
            "send_reaction", "remove_reaction", "create_poll", "vote_poll", "close_poll",

            // Inline buttons (press triggers state change)
            "press_inline_button",

            // Channels & invite links
            "get_invite_link", "revoke_invite_link", "join_chat_by_link",
            "subscribe_public_channel",

            // Chat folders
            "configure_chat_folder", "delete_chat_folder", "reorder_chat_folders",

            // Scheduled messages
            "schedule_message", "reschedule_message", "cancel_scheduled_message",

            // Account privacy, bot command menus, and detailed group permissions
            "set_privacy_settings", "set_bot_commands",
            "set_group_permissions", "set_member_permissions", "set_admin_rights",

            // Server policy changes
            "register_internal_chat",
        )

        /** Default destructive tools requiring confirmation. */
        val DEFAULT_DESTRUCTIVE_TOOLS = setOf(
            "delete_message",
            "leave_chat",
            "ban_user",
            "delete_contact",
            "block_user",
            "create_channel",
            "create_supergroup",
            "join_chat_by_link",
            "delete_chat_photo",
            "delete_profile_photo",
            "delete_chat_folder",
            "cancel_scheduled_message",
            "set_group_permissions",
            "set_member_permissions",
            "set_admin_rights",
            "close_poll",
            "revoke_invite_link",
            "set_slow_mode",
        )

        /**
         * Standard MCP behavior hints derived from the same policy tables that
         * enforce writes and confirmations at invocation time.
         */
        fun annotationsFor(
            toolName: String,
            additionalDestructiveTools: Collection<String> = emptyList(),
        ): McpSchema.ToolAnnotations {
            val readOnly = toolName !in WRITE_TOOLS
            val destructive = !readOnly &&
                (toolName in DEFAULT_DESTRUCTIVE_TOOLS || toolName in additionalDestructiveTools)
            return McpSchema.ToolAnnotations.builder()
                .readOnlyHint(readOnly)
                .destructiveHint(destructive)
                // Telegram operations are network calls. Do not imply that
                // automatic retries are safe unless a tool proves otherwise.
                .idempotentHint(false)
                .openWorldHint(true)
                .build()
        }
    }

    /** The effective set of destructive tool names. */
    private val destructiveTools: Set<String> =
        if (props.confirmation.destructiveTools.isNotEmpty()) {
            props.confirmation.destructiveTools.toSet()
        } else {
            DEFAULT_DESTRUCTIVE_TOOLS
        }

    /**
     * Checks whether the tool invocation is permitted.
     *
     * @param toolName  the MCP tool being invoked
     * @param arguments the tool input arguments (checked for "confirmed" key)
     * @throws ReadOnlyModeException if server is in read-only mode and tool is a write operation
     * @throws ConfirmationRequiredException if tool is destructive and not confirmed
     */
    fun checkPermission(toolName: String, arguments: Map<String, Any>) {
        // 1. Read-only mode check
        if (props.readOnly && toolName in WRITE_TOOLS) {
            log.warn("Blocked write tool '{}' — server is in read-only mode", toolName)
            throw ReadOnlyModeException(toolName)
        }

        // 2. Confirmation check for destructive operations
        if (props.confirmation.enabled && toolName in destructiveTools) {
            val confirmed = arguments["confirmed"]
            if (confirmed != true && confirmed?.toString()?.lowercase() != "true") {
                log.info("Destructive tool '{}' requires confirmation", toolName)
                throw ConfirmationRequiredException(
                    toolName,
                    getDestructiveDescription(toolName),
                )
            }
            log.info("Destructive tool '{}' confirmed by caller", toolName)
        }

        // 3. Anti-spam check (rate limits, daily caps, duplicate detection).
        antiSpamGuardService.check(toolName, arguments)
    }

    /**
     * Whether the server is currently in read-only mode.
     */
    fun isReadOnly(): Boolean = props.readOnly

    /**
     * Whether the given tool is classified as a write operation.
     */
    fun isWriteTool(toolName: String): Boolean = toolName in WRITE_TOOLS

    /**
     * Whether the given tool requires confirmation.
     */
    @Suppress("unused")
    fun requiresConfirmation(toolName: String): Boolean =
        props.confirmation.enabled && toolName in destructiveTools

    private fun getDestructiveDescription(toolName: String): String {
        return when (toolName) {
            "delete_message" -> "This will permanently delete a message"
            "leave_chat" -> "This will leave the chat/group/channel"
            "ban_user" -> "This will ban a user from the chat"
            "delete_contact" -> "This will remove the contact"
            "block_user" -> "This will block the user"
            "create_channel" -> "This will create a new supergroup or channel owned by the current account"
            "create_supergroup" -> "This will create a new private supergroup with topics and photo owned by the current account"
            "join_chat_by_link" -> "This will join a chat via the supplied invite link"
            "delete_chat_photo" -> "This will permanently remove the chat photo"
            "delete_profile_photo" -> "This will permanently delete the profile photo"
            "set_group_permissions" -> "This will change default permissions for every group member"
            "set_member_permissions" -> "This will change a member's ability to participate in the group"
            "set_admin_rights" -> "This will replace the member's administrator privileges"
            "close_poll" -> "This will permanently close the poll for all voters"
            "revoke_invite_link" -> "This will permanently invalidate the invite link"
            "set_slow_mode" -> "This will rate-limit every non-admin member of the group"
            else -> "This is a destructive operation"
        }
    }
}
