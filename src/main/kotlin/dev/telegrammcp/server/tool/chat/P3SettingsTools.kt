package dev.telegrammcp.server.tool.chat

import com.fasterxml.jackson.databind.ObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.exception.InvalidToolInputException
import dev.telegrammcp.server.model.BotCommand
import dev.telegrammcp.server.model.BotCommandScope
import dev.telegrammcp.server.model.BotCommandScopeKind
import dev.telegrammcp.server.model.ChatAdministratorRights
import dev.telegrammcp.server.model.ChatPermissions
import dev.telegrammcp.server.model.PrivacyRule
import dev.telegrammcp.server.model.PrivacyRuleKind
import dev.telegrammcp.server.model.PrivacySetting
import dev.telegrammcp.server.model.PrivacySettingRules
import dev.telegrammcp.server.service.AuditService
import dev.telegrammcp.server.service.EntityResolverService
import dev.telegrammcp.server.service.GuardrailService
import dev.telegrammcp.server.service.OperationGuardService
import dev.telegrammcp.server.tool.McpToolHandler
import dev.telegrammcp.server.tool.ToolSupport
import dev.telegrammcp.server.util.StructuredLogger
import io.micrometer.core.instrument.MeterRegistry
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import org.springframework.stereotype.Component

/** Focused account privacy controls. A write always replaces one complete rule set. */
@Component
class GetPrivacySettingsTool(
    private val telegramClient: TelegramClientService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {
    companion object { const val TOOL_NAME = "get_privacy_settings" }
    private val log = StructuredLogger.forClass<GetPrivacySettingsTool>()

    override fun definition(): McpSchema.Tool = ToolSupport.definition(
        TOOL_NAME,
        "Get the complete Telegram privacy-rule set for one account setting",
        """{"type":"object","properties":{"setting":{"type":"string","enum":["show_status","show_profile_photo","show_phone_number","show_link_in_forwarded_messages","allow_chat_invites","allow_calls","allow_peer_to_peer_calls","allow_private_voice_and_video_notes"]}},"required":["setting"]}""",
        objectMapper,
    )

    override fun execute(exchange: McpSyncServerExchange, arguments: Map<String, Any>): McpSchema.CallToolResult =
        ToolSupport.execute(TOOL_NAME, arguments, objectMapper, meterRegistry, log, "Failed to get privacy settings", auditService) {
            telegramClient.getPrivacySettingRules(P3ToolInputs.privacySetting(arguments))
        }
}

@Component
class SetPrivacySettingsTool(
    private val telegramClient: TelegramClientService,
    private val entityResolver: EntityResolverService,
    private val guardrailService: GuardrailService,
    private val operationGuardService: OperationGuardService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {
    companion object { const val TOOL_NAME = "set_privacy_settings" }
    private val log = StructuredLogger.forClass<SetPrivacySettingsTool>()

    override fun definition(): McpSchema.Tool = ToolSupport.definition(
        TOOL_NAME,
        "Replace one Telegram privacy-rule set. Specify the base rule and any explicit user/chat exceptions.",
        """{"type":"object","properties":{"setting":{"type":"string"},"base_rule":{"type":"string","enum":["allow_all","restrict_all","allow_contacts","restrict_contacts"]},"allow_user_ids":{"type":"array","items":{"type":"number"}},"restrict_user_ids":{"type":"array","items":{"type":"number"}},"allow_chat_ids":{"type":"array","items":{"type":["string","number"]}},"restrict_chat_ids":{"type":"array","items":{"type":["string","number"]}},"allow_bots":{"type":"boolean"},"allow_premium_users":{"type":"boolean"}},"required":["setting","base_rule"]}""",
        objectMapper,
    )

    override fun execute(exchange: McpSyncServerExchange, arguments: Map<String, Any>): McpSchema.CallToolResult =
        ToolSupport.execute(TOOL_NAME, arguments, objectMapper, meterRegistry, log, "Failed to set privacy settings", auditService) {
            operationGuardService.checkPermission(TOOL_NAME, arguments)
            val rules = P3ToolInputs.privacyRules(arguments, entityResolver, guardrailService)
            telegramClient.setPrivacySettingRules(rules)
            mapOf("updated" to true, "setting" to rules.setting, "rules" to rules.rules)
        }
}

/** Bot command menus are exposed separately because Telegram accepts them only for bot accounts. */
@Component
class GetBotCommandsTool(
    private val telegramClient: TelegramClientService,
    private val entityResolver: EntityResolverService,
    private val guardrailService: GuardrailService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {
    companion object { const val TOOL_NAME = "get_bot_commands" }
    private val log = StructuredLogger.forClass<GetBotCommandsTool>()

    override fun definition(): McpSchema.Tool = ToolSupport.definition(TOOL_NAME, "Get a bot command menu for a Telegram audience scope", P3ToolInputs.BOT_SCOPE_SCHEMA, objectMapper)

    override fun execute(exchange: McpSyncServerExchange, arguments: Map<String, Any>): McpSchema.CallToolResult =
        ToolSupport.execute(TOOL_NAME, arguments, objectMapper, meterRegistry, log, "Failed to get bot commands", auditService) {
            val scope = P3ToolInputs.botCommandScope(arguments, entityResolver, guardrailService)
            val language = P3ToolInputs.languageCode(arguments)
            mapOf("scope" to scope, "language_code" to language, "commands" to telegramClient.getBotCommands(scope, language))
        }
}

@Component
class SetBotCommandsTool(
    private val telegramClient: TelegramClientService,
    private val entityResolver: EntityResolverService,
    private val guardrailService: GuardrailService,
    private val operationGuardService: OperationGuardService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {
    companion object { const val TOOL_NAME = "set_bot_commands" }
    private val log = StructuredLogger.forClass<SetBotCommandsTool>()

    override fun definition(): McpSchema.Tool = ToolSupport.definition(
        TOOL_NAME,
        "Replace a bot command menu for a Telegram audience scope. Telegram rejects this operation for user accounts.",
        P3ToolInputs.BOT_COMMANDS_SCHEMA,
        objectMapper,
    )

    override fun execute(exchange: McpSyncServerExchange, arguments: Map<String, Any>): McpSchema.CallToolResult =
        ToolSupport.execute(TOOL_NAME, arguments, objectMapper, meterRegistry, log, "Failed to set bot commands", auditService) {
            operationGuardService.checkPermission(TOOL_NAME, arguments)
            val scope = P3ToolInputs.botCommandScope(arguments, entityResolver, guardrailService)
            val language = P3ToolInputs.languageCode(arguments)
            val commands = P3ToolInputs.botCommands(arguments)
            telegramClient.setBotCommands(scope, language, commands)
            mapOf("updated" to true, "scope" to scope, "language_code" to language, "commands" to commands)
        }
}

@Component
class GetGroupPermissionsTool(
    private val telegramClient: TelegramClientService,
    private val entityResolver: EntityResolverService,
    private val guardrailService: GuardrailService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {
    companion object { const val TOOL_NAME = "get_group_permissions" }
    private val log = StructuredLogger.forClass<GetGroupPermissionsTool>()

    override fun definition(): McpSchema.Tool = ToolSupport.definition(TOOL_NAME, "Get default permissions for a Telegram group or channel", P3ToolInputs.CHAT_SCHEMA, objectMapper)

    override fun execute(exchange: McpSyncServerExchange, arguments: Map<String, Any>): McpSchema.CallToolResult =
        ToolSupport.execute(TOOL_NAME, arguments, objectMapper, meterRegistry, log, "Failed to get group permissions", auditService) {
            val chatId = P3ToolInputs.chatId(arguments, entityResolver, guardrailService)
            mapOf("chat_id" to chatId, "permissions" to telegramClient.getChatPermissions(chatId))
        }
}

@Component
class SetGroupPermissionsTool(
    private val telegramClient: TelegramClientService,
    private val entityResolver: EntityResolverService,
    private val guardrailService: GuardrailService,
    private val operationGuardService: OperationGuardService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {
    companion object { const val TOOL_NAME = "set_group_permissions" }
    private val log = StructuredLogger.forClass<SetGroupPermissionsTool>()

    override fun definition(): McpSchema.Tool = ToolSupport.definition(TOOL_NAME, "Update selected default group permissions; omitted flags preserve their current values", P3ToolInputs.PERMISSIONS_SCHEMA, objectMapper)

    override fun execute(exchange: McpSyncServerExchange, arguments: Map<String, Any>): McpSchema.CallToolResult =
        ToolSupport.execute(TOOL_NAME, arguments, objectMapper, meterRegistry, log, "Failed to set group permissions", auditService) {
            operationGuardService.checkPermission(TOOL_NAME, arguments)
            val chatId = P3ToolInputs.chatId(arguments, entityResolver, guardrailService)
            val permissions = P3ToolInputs.permissions(arguments, telegramClient.getChatPermissions(chatId))
            telegramClient.setChatPermissions(chatId, permissions)
            mapOf("updated" to true, "chat_id" to chatId, "permissions" to permissions)
        }
}

@Component
class SetMemberPermissionsTool(
    private val telegramClient: TelegramClientService,
    private val entityResolver: EntityResolverService,
    private val guardrailService: GuardrailService,
    private val operationGuardService: OperationGuardService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {
    companion object { const val TOOL_NAME = "set_member_permissions" }
    private val log = StructuredLogger.forClass<SetMemberPermissionsTool>()

    override fun definition(): McpSchema.Tool = ToolSupport.definition(
        TOOL_NAME,
        "Set member-specific group permissions without banning the member; omitted flags inherit the current group default",
        P3ToolInputs.MEMBER_PERMISSIONS_SCHEMA,
        objectMapper,
    )

    override fun execute(exchange: McpSyncServerExchange, arguments: Map<String, Any>): McpSchema.CallToolResult =
        ToolSupport.execute(TOOL_NAME, arguments, objectMapper, meterRegistry, log, "Failed to set member permissions", auditService) {
            operationGuardService.checkPermission(TOOL_NAME, arguments)
            val chatId = P3ToolInputs.chatId(arguments, entityResolver, guardrailService)
            val userId = P3ToolInputs.userId(arguments)
            val permissions = P3ToolInputs.permissions(arguments, telegramClient.getChatPermissions(chatId))
            val untilDate = P3ToolInputs.optionalEpochSeconds(arguments, "until_date") ?: 0
            val isMember = P3ToolInputs.optionalBoolean(arguments, "is_member", true)
            telegramClient.setChatMemberPermissions(chatId, userId, permissions, untilDate, isMember)
            mapOf("updated" to true, "chat_id" to chatId, "user_id" to userId, "until_date" to untilDate, "permissions" to permissions)
        }
}

@Component
class SetAdminRightsTool(
    private val telegramClient: TelegramClientService,
    private val entityResolver: EntityResolverService,
    private val guardrailService: GuardrailService,
    private val operationGuardService: OperationGuardService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {
    companion object { const val TOOL_NAME = "set_admin_rights" }
    private val log = StructuredLogger.forClass<SetAdminRightsTool>()

    override fun definition(): McpSchema.Tool = ToolSupport.definition(
        TOOL_NAME,
        "Replace a member's complete administrator-rights set. Every omitted right is revoked.",
        P3ToolInputs.ADMIN_RIGHTS_SCHEMA,
        objectMapper,
    )

    override fun execute(exchange: McpSyncServerExchange, arguments: Map<String, Any>): McpSchema.CallToolResult =
        ToolSupport.execute(TOOL_NAME, arguments, objectMapper, meterRegistry, log, "Failed to set administrator rights", auditService) {
            operationGuardService.checkPermission(TOOL_NAME, arguments)
            val chatId = P3ToolInputs.chatId(arguments, entityResolver, guardrailService)
            val userId = P3ToolInputs.userId(arguments)
            val rights = P3ToolInputs.administratorRights(arguments)
            telegramClient.setChatMemberAdministratorRights(chatId, userId, rights)
            mapOf("updated" to true, "chat_id" to chatId, "user_id" to userId, "rights" to rights)
        }
}

private object P3ToolInputs {
    const val CHAT_SCHEMA = """{"type":"object","properties":{"chat_id":{"type":["string","number"]}},"required":["chat_id"]}"""
    const val BOT_SCOPE_SCHEMA = """{"type":"object","properties":{"scope":{"type":"string","enum":["default","all_private_chats","all_group_chats","all_chat_administrators","chat","chat_administrators","chat_member"]},"language_code":{"type":"string","maxLength":35},"chat_id":{"type":["string","number"]},"user_id":{"type":"number"}},"required":["scope"]}"""
    const val BOT_COMMANDS_SCHEMA = """{"type":"object","properties":{"scope":{"type":"string","enum":["default","all_private_chats","all_group_chats","all_chat_administrators","chat","chat_administrators","chat_member"]},"language_code":{"type":"string","maxLength":35},"chat_id":{"type":["string","number"]},"user_id":{"type":"number"},"commands":{"type":"array","maxItems":100,"items":{"type":"object","properties":{"command":{"type":"string"},"description":{"type":"string"}},"required":["command","description"]}}},"required":["scope","commands"]}"""
    const val PERMISSIONS_SCHEMA = """{"type":"object","properties":{"chat_id":{"type":["string","number"]},"permissions":{"type":"object","description":"One or more permission flags; omitted flags keep their current value","properties":{"can_send_basic_messages":{"type":"boolean"},"can_send_audios":{"type":"boolean"},"can_send_documents":{"type":"boolean"},"can_send_photos":{"type":"boolean"},"can_send_videos":{"type":"boolean"},"can_send_video_notes":{"type":"boolean"},"can_send_voice_notes":{"type":"boolean"},"can_send_polls":{"type":"boolean"},"can_send_other_messages":{"type":"boolean"},"can_add_link_previews":{"type":"boolean"},"can_react_to_messages":{"type":"boolean"},"can_edit_tag":{"type":"boolean"},"can_change_info":{"type":"boolean"},"can_invite_users":{"type":"boolean"},"can_pin_messages":{"type":"boolean"},"can_create_topics":{"type":"boolean"}}},"confirmed":{"type":"boolean","description":"Required when confirmation mode is enabled"}},"required":["chat_id","permissions"]}"""
    const val MEMBER_PERMISSIONS_SCHEMA = """{"type":"object","properties":{"chat_id":{"type":["string","number"]},"user_id":{"type":"number"},"permissions":{"type":"object"},"until_date":{"type":"number","description":"Unix timestamp; 0 means no expiry"},"is_member":{"type":"boolean","default":true},"confirmed":{"type":"boolean","description":"Required when confirmation mode is enabled"}},"required":["chat_id","user_id","permissions"]}"""
    const val ADMIN_RIGHTS_SCHEMA = """{"type":"object","properties":{"chat_id":{"type":["string","number"]},"user_id":{"type":"number"},"rights":{"type":"object","description":"Complete replacement; omitted rights are false","properties":{"can_manage_chat":{"type":"boolean"},"can_change_info":{"type":"boolean"},"can_post_messages":{"type":"boolean"},"can_edit_messages":{"type":"boolean"},"can_delete_messages":{"type":"boolean"},"can_invite_users":{"type":"boolean"},"can_restrict_members":{"type":"boolean"},"can_pin_messages":{"type":"boolean"},"can_manage_topics":{"type":"boolean"},"can_promote_members":{"type":"boolean"},"can_manage_video_chats":{"type":"boolean"},"can_post_stories":{"type":"boolean"},"can_edit_stories":{"type":"boolean"},"can_delete_stories":{"type":"boolean"},"can_manage_direct_messages":{"type":"boolean"},"can_manage_tags":{"type":"boolean"},"is_anonymous":{"type":"boolean"}}},"confirmed":{"type":"boolean","description":"Required when confirmation mode is enabled"}},"required":["chat_id","user_id","rights"]}"""

    fun privacySetting(arguments: Map<String, Any>): PrivacySetting = enumValue(arguments, "setting")

    fun privacyRules(
        arguments: Map<String, Any>,
        entityResolver: EntityResolverService,
        guardrailService: GuardrailService,
    ): PrivacySettingRules {
        val allowUsers = userIds(arguments, "allow_user_ids")
        val restrictUsers = userIds(arguments, "restrict_user_ids")
        requireNoOverlap(allowUsers, restrictUsers, "allow_user_ids", "restrict_user_ids")
        val allowChats = chatIds(arguments, "allow_chat_ids", entityResolver, guardrailService)
        val restrictChats = chatIds(arguments, "restrict_chat_ids", entityResolver, guardrailService)
        requireNoOverlap(allowChats, restrictChats, "allow_chat_ids", "restrict_chat_ids")
        val base: PrivacyRuleKind = enumValue(arguments, "base_rule")
        if (base !in setOf(PrivacyRuleKind.ALLOW_ALL, PrivacyRuleKind.RESTRICT_ALL, PrivacyRuleKind.ALLOW_CONTACTS, PrivacyRuleKind.RESTRICT_CONTACTS)) {
            throw InvalidToolInputException("base_rule must be allow_all, restrict_all, allow_contacts, or restrict_contacts")
        }
        val rules = buildList {
            if (restrictUsers.isNotEmpty()) add(PrivacyRule(PrivacyRuleKind.RESTRICT_USERS, userIds = restrictUsers))
            if (allowUsers.isNotEmpty()) add(PrivacyRule(PrivacyRuleKind.ALLOW_USERS, userIds = allowUsers))
            if (restrictChats.isNotEmpty()) add(PrivacyRule(PrivacyRuleKind.RESTRICT_CHAT_MEMBERS, chatIds = restrictChats))
            if (allowChats.isNotEmpty()) add(PrivacyRule(PrivacyRuleKind.ALLOW_CHAT_MEMBERS, chatIds = allowChats))
            arguments["allow_bots"]?.let { add(PrivacyRule(if (booleanValue(it, "allow_bots")) PrivacyRuleKind.ALLOW_BOTS else PrivacyRuleKind.RESTRICT_BOTS)) }
            if (optionalBoolean(arguments, "allow_premium_users", false)) add(PrivacyRule(PrivacyRuleKind.ALLOW_PREMIUM_USERS))
            add(PrivacyRule(base))
        }
        return PrivacySettingRules(privacySetting(arguments), rules)
    }

    fun botCommandScope(
        arguments: Map<String, Any>,
        entityResolver: EntityResolverService,
        guardrailService: GuardrailService,
    ): BotCommandScope {
        val kind: BotCommandScopeKind = enumValue(arguments, "scope")
        val chatId = when (kind) {
            BotCommandScopeKind.CHAT, BotCommandScopeKind.CHAT_ADMINISTRATORS, BotCommandScopeKind.CHAT_MEMBER -> chatId(arguments, entityResolver, guardrailService)
            else -> null
        }
        val userId = if (kind == BotCommandScopeKind.CHAT_MEMBER) userId(arguments) else null
        return BotCommandScope(kind, chatId, userId)
    }

    fun languageCode(arguments: Map<String, Any>): String {
        val value = arguments["language_code"]?.toString()?.trim().orEmpty()
        if (!value.matches(Regex("^[A-Za-z0-9_-]{0,35}$"))) throw InvalidToolInputException("language_code must be up to 35 letters, digits, underscores, or hyphens")
        return value
    }

    fun botCommands(arguments: Map<String, Any>): List<BotCommand> {
        val raw = arguments["commands"] as? List<*> ?: throw InvalidToolInputException("commands must be an array")
        if (raw.size > 100) throw InvalidToolInputException("commands supports at most 100 entries")
        val commands = raw.mapIndexed { index, value ->
            val entry = value as? Map<*, *> ?: throw InvalidToolInputException("commands[$index] must be an object")
            val command = entry["command"]?.toString()?.trim()?.lowercase().orEmpty()
            val description = entry["description"]?.toString()?.trim().orEmpty()
            if (!command.matches(Regex("^[a-z0-9_]{1,32}$"))) throw InvalidToolInputException("commands[$index].command must use 1-32 lowercase letters, digits, or underscores")
            if (description.isEmpty() || description.length > 256) throw InvalidToolInputException("commands[$index].description must contain 1-256 characters")
            BotCommand(command, description)
        }
        if (commands.map(BotCommand::command).toSet().size != commands.size) throw InvalidToolInputException("commands must not contain duplicate command names")
        return commands
    }

    fun chatId(arguments: Map<String, Any>, entityResolver: EntityResolverService, guardrailService: GuardrailService): Long {
        val raw = arguments["chat_id"] ?: throw InvalidToolInputException("chat_id is required")
        return entityResolver.resolve(raw).also(guardrailService::validateChatAccess)
    }

    fun userId(arguments: Map<String, Any>): Long = number(arguments["user_id"], "user_id", positive = true)

    fun permissions(arguments: Map<String, Any>, base: ChatPermissions): ChatPermissions {
        val raw = objectArgument(arguments, "permissions")
        if (raw.isEmpty()) throw InvalidToolInputException("permissions must contain at least one flag")
        return base.copy(
            canSendBasicMessages = flag(raw, "can_send_basic_messages", base.canSendBasicMessages),
            canSendAudios = flag(raw, "can_send_audios", base.canSendAudios),
            canSendDocuments = flag(raw, "can_send_documents", base.canSendDocuments),
            canSendPhotos = flag(raw, "can_send_photos", base.canSendPhotos),
            canSendVideos = flag(raw, "can_send_videos", base.canSendVideos),
            canSendVideoNotes = flag(raw, "can_send_video_notes", base.canSendVideoNotes),
            canSendVoiceNotes = flag(raw, "can_send_voice_notes", base.canSendVoiceNotes),
            canSendPolls = flag(raw, "can_send_polls", base.canSendPolls),
            canSendOtherMessages = flag(raw, "can_send_other_messages", base.canSendOtherMessages),
            canAddLinkPreviews = flag(raw, "can_add_link_previews", base.canAddLinkPreviews),
            canReactToMessages = flag(raw, "can_react_to_messages", base.canReactToMessages),
            canEditTag = flag(raw, "can_edit_tag", base.canEditTag),
            canChangeInfo = flag(raw, "can_change_info", base.canChangeInfo),
            canInviteUsers = flag(raw, "can_invite_users", base.canInviteUsers),
            canPinMessages = flag(raw, "can_pin_messages", base.canPinMessages),
            canCreateTopics = flag(raw, "can_create_topics", base.canCreateTopics),
        )
    }

    fun administratorRights(arguments: Map<String, Any>): ChatAdministratorRights {
        val raw = objectArgument(arguments, "rights")
        if (raw.isEmpty()) throw InvalidToolInputException("rights must contain at least one flag")
        return ChatAdministratorRights(
            canManageChat = flag(raw, "can_manage_chat", false),
            canChangeInfo = flag(raw, "can_change_info", false),
            canPostMessages = flag(raw, "can_post_messages", false),
            canEditMessages = flag(raw, "can_edit_messages", false),
            canDeleteMessages = flag(raw, "can_delete_messages", false),
            canInviteUsers = flag(raw, "can_invite_users", false),
            canRestrictMembers = flag(raw, "can_restrict_members", false),
            canPinMessages = flag(raw, "can_pin_messages", false),
            canManageTopics = flag(raw, "can_manage_topics", false),
            canPromoteMembers = flag(raw, "can_promote_members", false),
            canManageVideoChats = flag(raw, "can_manage_video_chats", false),
            canPostStories = flag(raw, "can_post_stories", false),
            canEditStories = flag(raw, "can_edit_stories", false),
            canDeleteStories = flag(raw, "can_delete_stories", false),
            canManageDirectMessages = flag(raw, "can_manage_direct_messages", false),
            canManageTags = flag(raw, "can_manage_tags", false),
            isAnonymous = flag(raw, "is_anonymous", false),
        )
    }

    fun optionalEpochSeconds(arguments: Map<String, Any>, name: String): Int? {
        val value = arguments[name] ?: return null
        val seconds = number(value, name, positive = false)
        if (seconds > Int.MAX_VALUE) throw InvalidToolInputException("$name is outside TDLib's supported range")
        return seconds.toInt()
    }

    fun optionalBoolean(arguments: Map<String, Any>, name: String, default: Boolean): Boolean = arguments[name]?.let { booleanValue(it, name) } ?: default

    private fun userIds(arguments: Map<String, Any>, name: String): List<Long> = arrayArgument(arguments, name).mapIndexed { index, value ->
        number(value, "$name[$index]", positive = true)
    }.distinct()

    private fun chatIds(arguments: Map<String, Any>, name: String, entityResolver: EntityResolverService, guardrailService: GuardrailService): List<Long> =
        arrayArgument(arguments, name).mapIndexed { index, value ->
            entityResolver.resolve(value ?: throw InvalidToolInputException("$name[$index] must be a chat identifier"))
        }.distinct().onEach(guardrailService::validateChatAccess)

    private fun arrayArgument(arguments: Map<String, Any>, name: String): List<*> = when (val raw = arguments[name]) {
        null -> emptyList<Any>()
        is List<*> -> raw
        else -> throw InvalidToolInputException("$name must be an array")
    }

    @Suppress("UNCHECKED_CAST")
    private fun objectArgument(arguments: Map<String, Any>, name: String): Map<String, Any> =
        (arguments[name] as? Map<*, *>)?.entries?.associate { (key, value) -> key?.toString().orEmpty() to (value ?: throw InvalidToolInputException("$name values must not be null")) }
            ?: throw InvalidToolInputException("$name must be an object")

    private fun flag(values: Map<String, Any>, name: String, default: Boolean): Boolean = values[name]?.let { booleanValue(it, name) } ?: default

    private fun booleanValue(value: Any, name: String): Boolean = when (value) {
        is Boolean -> value
        is String -> when (value.lowercase()) {
            "true" -> true
            "false" -> false
            else -> throw InvalidToolInputException("$name must be a boolean")
        }
        else -> throw InvalidToolInputException("$name must be a boolean")
    }

    private fun number(value: Any?, name: String, positive: Boolean): Long {
        val parsed = when (value) {
            is Number -> value.toLong().takeIf { value.toDouble() == it.toDouble() }
            else -> value?.toString()?.trim()?.toLongOrNull()
        } ?: throw InvalidToolInputException("$name must be an integer")
        if (positive && parsed <= 0) throw InvalidToolInputException("$name must be positive")
        if (!positive && parsed < 0) throw InvalidToolInputException("$name must not be negative")
        return parsed
    }

    private inline fun <reified T : Enum<T>> enumValue(arguments: Map<String, Any>, name: String): T {
        val raw = arguments[name]?.toString()?.trim()?.uppercase()?.replace('-', '_')
            ?: throw InvalidToolInputException("$name is required")
        return enumValues<T>().firstOrNull { it.name == raw }
            ?: throw InvalidToolInputException("$name has an unsupported value: ${arguments[name]}")
    }

    private fun requireNoOverlap(first: List<Long>, second: List<Long>, firstName: String, secondName: String) {
        if ((first intersect second.toSet()).isNotEmpty()) throw InvalidToolInputException("$firstName and $secondName must not overlap")
    }
}
