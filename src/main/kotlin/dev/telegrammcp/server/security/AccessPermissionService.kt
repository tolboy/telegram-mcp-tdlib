package dev.telegrammcp.server.security

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import dev.telegrammcp.server.config.McpSecurityProperties
import dev.telegrammcp.server.service.OperationGuardService
import dev.telegrammcp.server.service.PlatformPaths
import kotlinx.coroutines.asContextElement
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import java.nio.file.Files

class PermissionDeniedException : RuntimeException("The client is not permitted to perform this operation in the selected scope")

data class PermissionDocument(val version: Int, val grants: List<PermissionGrant>)
data class PermissionGrant(
    val client: String,
    val account: String,
    val actions: Set<String>,
    @param:JsonProperty("chat_ids") @get:JsonProperty("chat_ids") val chatIds: Set<Long>? = null,
)

/** Immutable, invocation-scoped authority; explicitly propagated into coroutine workers. */
object AccessPermissionContext {
    data class Scope(val chatIds: Set<Long>?)
    private val selected = ThreadLocal<Scope?>()
    fun current(): Scope? = selected.get()
    fun coroutineContext() = selected.asContextElement()
    fun <T> withScope(scope: Scope, action: () -> T): T {
        val previous = selected.get()
        selected.set(scope)
        return try { action() } finally {
            if (previous == null) selected.remove() else selected.set(previous)
        }
    }
}

@Service
class AccessPermissionService(props: McpSecurityProperties, paths: PlatformPaths, mapper: ObjectMapper) {
    private val document: PermissionDocument? = props.permissionsFile.takeIf(String::isNotBlank)?.let { raw ->
        val path = paths.resolveApplicationPath(raw, "MCP_PERMISSIONS_FILE")
        require(Files.size(path) <= 1_048_576) { "Permission file exceeds 1 MiB" }
        val strictMapper = mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        val tree = strictMapper.readTree(Files.readAllBytes(path))
        require(tree.path("grants").all { it.has("chat_ids") }) { "Each grant must explicitly specify chat_ids (null means all chats)" }
        strictMapper.treeToValue(tree, PermissionDocument::class.java).also(::validate)
    }
    val enabled: Boolean get() = document != null

    private fun matching(account: String) = document?.grants.orEmpty().filter {
        it.client == clientIdentity() && (it.account == account || it.account == "*")
    }

    fun isAccountVisible(account: String): Boolean = !enabled || matching(account).isNotEmpty()

    fun <T> withPermission(tool: String, account: String, action: () -> T): T {
        if (!enabled) return action()
        val grants = matching(account).filter { actionFor(tool) in it.actions }
        if (grants.isEmpty()) throw PermissionDeniedException()
        val chats = if (grants.any { it.chatIds == null }) null else grants.flatMap { it.chatIds.orEmpty() }.toSet()
        // Account-wide tools cannot be made safe by pretending that their user IDs are chat scopes.
        if (chats != null && (chats.isEmpty() || tool !in CHAT_SCOPED_TOOLS)) throw PermissionDeniedException()
        return AccessPermissionContext.withScope(AccessPermissionContext.Scope(chats), action)
    }

    /** Missing invocation authority fails closed even for a direct handler call. */
    fun requestChatIds(): Set<Long>? = if (!enabled) null else AccessPermissionContext.current()?.chatIds
        ?: if (AccessPermissionContext.current() == null) emptySet() else null

    companion object {
        val ACTIONS = setOf("read", "download", "mutate", "quota", "policy")
        fun actionFor(tool: String): String = when (tool) {
            "download_media" -> "download"
            "transcribe_voice_note" -> "quota"
            "register_internal_chat" -> "policy"
            in OperationGuardService.WRITE_TOOLS -> "mutate"
            else -> "read"
        }

        fun clientIdentity(): String = SecurityContextHolder.getContext().authentication?.name ?: "stdio"

        fun validate(document: PermissionDocument) {
            require(document.version == 1) { "Unsupported permission file version" }
            require(document.grants.size <= 1000) { "Too many permission grants" }
            document.grants.forEach { grant ->
                require(grant.client.isNotBlank() && grant.client.length <= 256 && grant.client != "*") { "An exact client identity is required" }
                require(grant.account == "*" || grant.account.matches(Regex("[a-z0-9][a-z0-9_-]{0,63}"))) { "Invalid account label" }
                require(grant.actions.isNotEmpty() && ACTIONS.containsAll(grant.actions)) { "Unknown or empty permission actions" }
                require(grant.chatIds == null || (grant.chatIds.size <= 10000 && 0L !in grant.chatIds)) { "Invalid chat IDs" }
            }
        }

        // These handlers validate target IDs or filter all returned chat-bearing records.
        // New tools fail closed for chat-scoped grants until explicitly reviewed here.
        val CHAT_SCOPED_TOOLS = setOf(
            "archive_chat", "unarchive_chat", "mute_chat", "unmute_chat", "ban_user", "unban_user",
            "promote_admin", "demote_admin", "close_forum_topic", "reopen_forum_topic", "create_topic", "edit_forum_topic",
            "delete_chat_photo", "edit_chat_photo", "edit_chat_title", "get_admins", "get_banned_users", "get_chat",
            "get_invite_link", "get_participants", "get_recent_actions", "list_invite_links", "revoke_invite_link",
            "invite_to_group", "leave_chat", "list_chats", "list_topics", "search_public_chats", "set_chat_description",
            "set_forum_topics_enabled", "set_slow_mode", "subscribe_public_channel", "clear_draft", "get_drafts", "save_draft",
            "download_media", "get_media_info", "send_file", "send_sticker", "send_voice", "transcribe_voice_note",
            "create_poll", "delete_message", "edit_message", "forward_message", "get_history", "get_message_context",
            "get_message_link", "get_message_reactions", "get_messages", "get_message_viewers", "get_pinned_messages",
            "list_inline_buttons", "mark_as_read", "message_from_link", "pin_message", "unpin_message", "vote_poll", "close_poll",
            "press_inline_button", "remove_reaction", "reply_to_message", "search_global", "search_messages", "send_message",
            "send_reaction", "register_internal_chat", "discover_public_chats", "export_chat_history", "search_public_messages",
            "get_common_chats", "get_send_operation", "list_scheduled_messages", "schedule_message", "reschedule_message",
            "cancel_scheduled_message", "get_group_permissions", "set_group_permissions", "set_member_permissions", "set_admin_rights",
        )
    }
}
