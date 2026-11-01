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
 * 1. **Read-only mode** — blocks writes except explicitly permitted local downloads
 * 2. **Caller-acknowledgement mode** — requires explicit `"confirmed": true`
 *    for destructive operations (delete, ban, leave, etc.). Human approval
 *    (destructive and high-risk tools) is [DestructiveApprovalService]'s job.
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

        /** Downloads still write local files; this exception does not change MCP hints. */
        fun blockedByReadOnly(toolName: String, readOnly: Boolean, allowDownloads: Boolean = false): Boolean =
            readOnly && toolName in WRITE_TOOLS && !(allowDownloads && toolName == "download_media")

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
            // Loosens anti-spam thresholds for the target chat; a prompt-injected
            // call must not silently weaken its own guardrails.
            "register_internal_chat",
        )

        /**
         * Writes that are not destructive in the delete/ban sense but move data
         * out of the account, change who can do what, or trigger actions the
         * server cannot see. Human approval covers them by default.
         */
        val DEFAULT_HIGH_RISK_TOOLS = setOf(
            "forward_message",
            "send_file",
            "promote_admin",
            "demote_admin",
            "set_privacy_settings",
            "invite_to_group",
            "update_profile",
            "configure_chat_folder",
            "unban_user",
            "unblock_user",
            // A bot button can do anything the bot implements: pay, confirm, delete.
            "press_inline_button",
        )

        /**
         * Standard MCP behavior hints derived from the same policy tables that
         * enforce writes, confirmations, and human approval at invocation time.
         * `destructiveHint` is true exactly for the tools the approval gate covers.
         */
        fun annotationsFor(
            toolName: String,
            additionalDestructiveTools: Collection<String> = emptyList(),
        ): McpSchema.ToolAnnotations = annotationsFor(
            toolName,
            RiskPolicy(ServerModeProperties.ConfirmationProps(destructiveTools = additionalDestructiveTools.toList())),
        )

        fun annotationsFor(toolName: String, policy: RiskPolicy): McpSchema.ToolAnnotations {
            val readOnly = toolName !in WRITE_TOOLS
            val destructive = !readOnly && policy.requiresApproval(toolName)
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

    /**
     * Effective tool classification for one configuration.
     *
     * Configured lists add to the defaults. In 1.18.1 a non-empty
     * `destructive-tools` replaced them, so listing one extra tool silently
     * dropped confirmation and approval from `delete_message`, `ban_user`, …
     */
    class RiskPolicy(confirmation: ServerModeProperties.ConfirmationProps) {
        val destructiveTools: Set<String> =
            DEFAULT_DESTRUCTIVE_TOOLS + validated(confirmation.destructiveTools, "destructive-tools")

        val highRiskTools: Set<String> =
            (DEFAULT_HIGH_RISK_TOOLS + validated(confirmation.highRiskTools, "high-risk-tools")) - destructiveTools

        val scope: ServerModeProperties.ApprovalScope = confirmation.approvalScope

        /** Tools that ask for human approval when an approval mode is on. */
        val approvalTools: Set<String> = when (scope) {
            ServerModeProperties.ApprovalScope.DESTRUCTIVE -> destructiveTools
            ServerModeProperties.ApprovalScope.HIGH_RISK -> destructiveTools + highRiskTools
            ServerModeProperties.ApprovalScope.ALL_WRITES -> WRITE_TOOLS
        }

        fun requiresApproval(toolName: String): Boolean = toolName in approvalTools

        /** "destructive", "high-risk", or "write", for approval prompts and logs. */
        fun riskLabel(toolName: String): String = when (toolName) {
            in destructiveTools -> "destructive"
            in highRiskTools -> "high-risk"
            else -> "write"
        }

        private fun validated(names: List<String>, setting: String): Set<String> {
            val cleaned = names.map(String::trim).filter(String::isNotEmpty).toSet()
            val unknown = cleaned - WRITE_TOOLS
            require(unknown.isEmpty()) {
                "server-mode.confirmation.$setting lists ${unknown.sorted().joinToString()}, which " +
                    (if (unknown.size == 1) "is not a write tool" else "are not write tools") +
                    "; only write tools can be gated"
            }
            return cleaned
        }
    }

    val riskPolicy = RiskPolicy(props.confirmation)

    /** The effective set of destructive tool names (defaults plus configured additions). */
    private val destructiveTools: Set<String> = riskPolicy.destructiveTools

    /**
     * Checks whether the tool invocation is permitted.
     *
     * @param toolName  the MCP tool being invoked
     * @param arguments the tool input arguments (checked for "confirmed" key)
     * @throws ReadOnlyModeException if server is in read-only mode and tool is a write operation
     * @throws ConfirmationRequiredException if a destructive tool lacks caller acknowledgement
     */
    fun checkPermission(toolName: String, arguments: Map<String, Any>) {
        checkPolicy(toolName, arguments)
        antiSpamGuardService.check(toolName, arguments)
    }

    /** Revalidate access for a receipt replay without charging another send. */
    fun checkPolicy(toolName: String, arguments: Map<String, Any>) {
        // 1. Read-only mode check
        if (blockedByReadOnly(toolName, props.readOnly, props.readOnlyAllowDownloads)) {
            log.warn("Blocked write tool '{}' — server is in read-only mode", toolName)
            throw ReadOnlyModeException(toolName)
        }

        // 2. Confirmation check for destructive operations
        if (props.confirmation.enabled && toolName in destructiveTools) {
            val confirmed = arguments["confirmed"]
            if (confirmed != true && confirmed?.toString()?.lowercase() != "true") {
                log.info("Destructive tool '{}' requires caller acknowledgement", toolName)
                throw ConfirmationRequiredException(
                    toolName,
                    getDestructiveDescription(toolName),
                )
            }
            log.info("Destructive tool '{}' confirmed by caller", toolName)
        }

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
     * Whether the given tool requires caller acknowledgement.
     */
    @Suppress("unused")
    fun requiresConfirmation(toolName: String): Boolean =
        props.confirmation.enabled && toolName in destructiveTools

    /**
     * Whether the tool is classified as destructive, regardless of which gates
     * are switched on.
     *
     * Human approval keys off this rather than [requiresConfirmation]: the two
     * are configured independently, and reusing that predicate would silently
     * stop asking anyone the moment caller acknowledgement was turned off.
     */
    fun isDestructiveTool(toolName: String): Boolean = toolName in destructiveTools

    /**
     * Whether human approval covers this tool under the configured
     * [ServerModeProperties.ApprovalScope]. Independent of the approval mode,
     * like [isDestructiveTool].
     */
    fun requiresHumanApproval(toolName: String): Boolean = riskPolicy.requiresApproval(toolName)

    fun riskLabel(toolName: String): String = riskPolicy.riskLabel(toolName)

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
            "register_internal_chat" -> "This will loosen anti-spam rate limits for the chat"
            else -> "This is a destructive operation"
        }
    }
}
