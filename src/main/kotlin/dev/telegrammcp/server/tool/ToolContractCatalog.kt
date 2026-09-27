package dev.telegrammcp.server.tool

import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.model.*
import kotlin.reflect.typeOf

/** Explicit tool-to-wire contracts. DTO schemas follow their declared Kotlin fields. */
object ToolContractCatalog {
    private val s = WireSchemas.string
    private val i = WireSchemas.integer
    private val b = WireSchemas.boolean
    private val date = WireSchemas.date
    private fun array(schema: Map<String, Any>) = WireSchemas.array(schema)
    private fun obj(vararg fields: Pair<String, Map<String, Any>>) = WireSchemas.obj(*fields)
    private fun union(vararg schemas: Map<String, Any>) = WireSchemas.union(*schemas)
    private inline fun <reified T> dto() = WireSchemas.type(typeOf<T>())

    val schemas: Map<String, Map<String, Any>> by lazy {
        buildMap {
            fun method(method: String, vararg tools: String) {
                val schema = WireSchemas.type(TelegramClientService::class.members.single { it.name == method }.returnType)
                tools.forEach { put(it, schema) }
            }
            fun shape(tool: String, fields: String) {
                put(tool, WireSchemas.obj(fields.split(' ').associate { field ->
                    val (name, type) = field.split(':')
                    name to when (type) {
                        "s" -> s; "i" -> i; "b" -> b
                        "s?" -> union(s, WireSchemas.nil); "i?" -> union(i, WireSchemas.nil)
                        "ii" -> array(i); "date?" -> union(date, WireSchemas.nil)
                        else -> error("Unknown field descriptor: $field")
                    }
                }))
            }
            method("getChats", "list_chats")
            method("getChat", "get_chat", "create_group", "create_channel", "join_chat_by_link")
            method("listChatFolders", "list_chat_folders")
            method("getChatFolder", "get_chat_folder")
            method("createChatFolder", "configure_chat_folder")
            method("getChatMembers", "get_participants", "get_admins", "get_banned_users")
            method("getChatEventLog", "get_recent_actions")
            method("listForumTopics", "list_topics")
            method("createForumTopic", "create_topic")
            method("createInviteLink", "get_invite_link")
            method("searchPublicChats", "search_public_chats")
            method("getPrivacySettingRules", "get_privacy_settings")
            method("getDrafts", "get_drafts")
            method("downloadMedia", "download_media")
            method("getMediaInfo", "get_media_info")
            method("getInstalledStickerSets", "get_sticker_sets")
            method("transcribeVoiceNote", "transcribe_voice_note")
            method("getHistory", "get_history", "get_messages", "get_message_context", "get_pinned_messages", "search_messages", "search_global")
            method("getMessageLink", "get_message_link")
            method("getMessageReactions", "get_message_reactions")
            method("listInlineButtons", "list_inline_buttons")
            method("getMessageByLink", "message_from_link")
            method("getScheduledMessages", "list_scheduled_messages")
            method("getBlockedUsers", "get_blocked_users")
            method("getMe", "get_me")
            method("getUserProfilePhotos", "get_user_photos")
            method("getContacts", "list_contacts", "search_contacts")
            put("get_send_operation", ToolOutputSchemas.receipt)
            listOf("send_message", "reply_to_message", "send_file", "send_voice", "send_sticker").forEach { put(it, ToolOutputSchemas.sendResult) }
            put("forward_message", union(array(ToolOutputSchemas.message), ToolOutputSchemas.receipt))
            put("schedule_message", union(dto<ScheduledMessage>(), ToolOutputSchemas.receipt))
            listOf("edit_message", "create_poll").forEach { put(it, ToolOutputSchemas.message) }
            put("export_chat_history", ToolOutputSchemas.export)
            put("search_public_messages", ToolOutputSchemas.search)

            val flags = mapOf(
                "archive_chat" to "archived:b chat_id:i", "unarchive_chat" to "unarchived:b chat_id:i",
                "mute_chat" to "muted:b chat_id:i", "unmute_chat" to "unmuted:b chat_id:i",
                "ban_user" to "banned:b user_id:i chat_id:i", "unban_user" to "unbanned:b user_id:i chat_id:i",
                "demote_admin" to "demoted:b user_id:i chat_id:i", "promote_admin" to "promoted:b user_id:i chat_id:i",
                "delete_chat_photo" to "chat_id:i deleted:b", "edit_chat_photo" to "chat_id:i updated:b",
                "edit_chat_title" to "updated:b chat_id:i title:s", "invite_to_group" to "invited:i chat_id:i",
                "leave_chat" to "left:b chat_id:i", "set_chat_description" to "chat_id:i description:s updated:b",
                "set_slow_mode" to "chat_id:i delay_seconds:i updated:b",
                "set_forum_topics_enabled" to "chatId:i enabled:b updated:b",
                "close_forum_topic" to "chatId:i messageThreadId:i closed:b updated:b",
                "reopen_forum_topic" to "chatId:i messageThreadId:i closed:b updated:b",
                "edit_forum_topic" to "chatId:i messageThreadId:i name:s updated:b",
                "delete_chat_folder" to "folder_id:i deleted:b",
                "reorder_chat_folders" to "folder_ids:ii main_list_position:i reordered:b",
                "clear_draft" to "chat_id:i cleared:b", "save_draft" to "chat_id:i saved:b",
                "delete_message" to "deleted:i chat_id:i", "mark_as_read" to "marked_as_read:i chat_id:i",
                "pin_message" to "pinned:b message_id:i chat_id:i", "unpin_message" to "unpinned:b message_id:i chat_id:i",
                "vote_poll" to "chat_id:i message_id:i voted_option_ids:ii", "close_poll" to "chat_id:i message_id:i closed:b",
                "press_inline_button" to "chat_id:i message_id:i button_index:i? button_text:s? answer:s",
                "remove_reaction" to "chat_id:i message_id:i emoji:s removed:b",
                "send_reaction" to "chat_id:i message_id:i emoji:s is_big:b",
                "reschedule_message" to "chat_id:i message_id:i previous_message_id:i send_at:date? repeat_period_seconds:i rescheduled:b",
                "cancel_scheduled_message" to "chat_id:i message_id:i cancelled:b",
                "register_internal_chat" to "chat_id:i internal:b",
                "add_contact" to "added:b user_id:i", "block_user" to "blocked:b user_id:i",
                "unblock_user" to "unblocked:b user_id:i", "delete_contact" to "deleted:b user_id:i",
                "delete_profile_photo" to "deleted:b", "set_profile_photo" to "updated:b", "update_profile" to "updated:b",
                "get_user_status" to "user_id:i status:s",
            )
            flags.forEach { (name, fields) -> shape(name, fields) }
            put("list_invite_links", obj("chat_id" to i, "include_revoked" to b, "invite_links" to dto<List<ChatInviteLinkInfo>>()))
            put("revoke_invite_link", obj("chat_id" to i, "revoked" to b, "invite_links" to dto<List<ChatInviteLinkInfo>>()))
            put("set_privacy_settings", obj("updated" to b, "setting" to dto<PrivacySetting>(), "rules" to dto<List<PrivacyRule>>()))
            val commands = mapOf("scope" to dto<BotCommandScope>(), "language_code" to s, "commands" to dto<List<BotCommand>>())
            put("get_bot_commands", WireSchemas.obj(commands))
            put("set_bot_commands", WireSchemas.obj(commands + ("updated" to b)))
            val permissions = mapOf("chat_id" to i, "permissions" to dto<ChatPermissions>())
            put("get_group_permissions", WireSchemas.obj(permissions))
            put("set_group_permissions", WireSchemas.obj(permissions + ("updated" to b)))
            put("set_member_permissions", WireSchemas.obj(permissions + mapOf("updated" to b, "user_id" to i, "until_date" to i)))
            put("set_admin_rights", obj("updated" to b, "chat_id" to i, "user_id" to i, "rights" to dto<ChatAdministratorRights>()))
            put("get_message_viewers", obj("chat_id" to i, "message_id" to i, "viewers" to dto<List<MessageViewerInfo>>()))
            put("subscribe_public_channel", union(dto<ChatInfo>(), obj("channel" to s, "already_member" to b)))
            put("get_common_chats", obj("user_id" to i, "chats" to dto<List<ChatInfo>>()))
            put("get_last_interaction", union(ToolOutputSchemas.message, obj("contact_id" to i, "message" to WireSchemas.nil)))
            put("resolve_username", union(dto<UserInfo>(), dto<ChatInfo>(), obj("id" to i, "type" to s)))
            put("list_accounts", obj("accounts" to array(obj("label" to s)), "multi_account" to b, "selection_required" to b))
            put("discover_public_chats", obj("query" to s, "limit" to i, "allowlist_applied" to b,
                "results" to dto<List<ChatInfo>>(), "filtered_out" to i))
            put("create_supergroup", obj("chat_id" to i, "title" to s, "type" to s,
                "description" to union(s, WireSchemas.nil), "member_count" to union(i, WireSchemas.nil),
                "forum_topics_enabled" to b, "photo_set" to b, "warnings" to array(s),
                "topics" to array(obj("name" to s, "topic_id" to i, "message_thread_id" to i,
                    "icon_color" to union(i, WireSchemas.nil), "custom_emoji_id" to union(i, WireSchemas.nil)))))
            put("_manifest", obj("schemaVersion" to i, "serverVersion" to s, "connector" to s, "manifest" to s,
                "toolProfile" to s, "toolCount" to i, "selfChatAliases" to array(s), "routingHints" to array(s),
                "toolGroups" to mapOf("type" to "object", "additionalProperties" to array(s)),
                "tools" to array(obj("name" to s, "description" to s))))
        }
    }
}
