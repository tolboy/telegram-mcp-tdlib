package dev.telegrammcp.server.tool.chat

import com.fasterxml.jackson.databind.ObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.exception.InvalidToolInputException
import dev.telegrammcp.server.model.ChatFolderDefinition
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

@Component
class ListChatFoldersTool(
    private val telegramClient: TelegramClientService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {
    companion object { const val TOOL_NAME = "list_chat_folders" }
    private val log = StructuredLogger.forClass<ListChatFoldersTool>()

    override fun definition(): McpSchema.Tool = ToolSupport.definition(
        TOOL_NAME,
        "List Telegram chat folders known to TDLib for the selected account",
        """{"type":"object","properties":{},"required":[]}""",
        objectMapper,
    )

    override fun execute(exchange: McpSyncServerExchange, arguments: Map<String, Any>): McpSchema.CallToolResult =
        ToolSupport.execute(TOOL_NAME, arguments, objectMapper, meterRegistry, log, "Failed to list chat folders", auditService) {
            telegramClient.listChatFolders()
        }
}

@Component
class GetChatFolderTool(
    private val telegramClient: TelegramClientService,
    private val guardrailService: GuardrailService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {
    companion object {
        const val TOOL_NAME = "get_chat_folder"
        private const val INPUT_SCHEMA = """
        {"type":"object","properties":{"folder_id":{"type":"number","description":"Telegram chat folder ID"}},"required":["folder_id"]}
        """
    }
    private val log = StructuredLogger.forClass<GetChatFolderTool>()

    override fun definition(): McpSchema.Tool = ToolSupport.definition(TOOL_NAME, "Get the complete configuration of one Telegram chat folder", INPUT_SCHEMA, objectMapper)

    override fun execute(exchange: McpSyncServerExchange, arguments: Map<String, Any>): McpSchema.CallToolResult =
        ToolSupport.execute(TOOL_NAME, arguments, objectMapper, meterRegistry, log, "Failed to get chat folder", auditService) {
            val details = telegramClient.getChatFolder(ChatFolderInputs.requiredPositiveInt(arguments, "folder_id"))
            ChatFolderInputs.validateFolderChatAccess(details.definition, guardrailService)
            details
        }
}

/** Creates a folder or fully replaces an existing folder when folder_id is supplied. */
@Component
class ConfigureChatFolderTool(
    private val telegramClient: TelegramClientService,
    private val entityResolver: EntityResolverService,
    private val guardrailService: GuardrailService,
    private val operationGuardService: OperationGuardService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {
    companion object {
        const val TOOL_NAME = "configure_chat_folder"
        private const val INPUT_SCHEMA = """
        {
          "type":"object",
          "properties":{
            "folder_id":{"type":"number","description":"Existing folder ID to replace; omit to create a new folder"},
            "title":{"type":"string","description":"Folder title"},
            "icon_name":{"type":"string","description":"Optional Telegram folder icon name"},
            "color_id":{"type":"number","description":"Telegram folder color ID (default 0)"},
            "is_shareable":{"type":"boolean","description":"Whether the folder may be shared"},
            "pinned_chats":{"type":"array","items":{"type":["string","number"]},"description":"Pinned chat identifiers"},
            "included_chats":{"type":"array","items":{"type":["string","number"]},"description":"Explicitly included chat identifiers"},
            "excluded_chats":{"type":"array","items":{"type":["string","number"]},"description":"Explicitly excluded chat identifiers"},
            "exclude_muted":{"type":"boolean"}, "exclude_read":{"type":"boolean"}, "exclude_archived":{"type":"boolean"},
            "include_contacts":{"type":"boolean"}, "include_non_contacts":{"type":"boolean"}, "include_bots":{"type":"boolean"},
            "include_groups":{"type":"boolean"}, "include_channels":{"type":"boolean"}
          },
          "required":["title"]
        }
        """
    }
    private val log = StructuredLogger.forClass<ConfigureChatFolderTool>()

    override fun definition(): McpSchema.Tool = ToolSupport.definition(
        TOOL_NAME,
        "Create a Telegram chat folder or replace an existing folder's complete configuration",
        INPUT_SCHEMA,
        objectMapper,
    )

    override fun execute(exchange: McpSyncServerExchange, arguments: Map<String, Any>): McpSchema.CallToolResult =
        ToolSupport.execute(TOOL_NAME, arguments, objectMapper, meterRegistry, log, "Failed to configure chat folder", auditService) {
            operationGuardService.checkPermission(TOOL_NAME, arguments)
            val folder = ChatFolderInputs.definition(arguments, entityResolver, guardrailService)
            val folderId = ChatFolderInputs.optionalPositiveInt(arguments, "folder_id")
            if (folderId == null) telegramClient.createChatFolder(folder) else telegramClient.updateChatFolder(folderId, folder)
        }
}

@Component
class DeleteChatFolderTool(
    private val telegramClient: TelegramClientService,
    private val operationGuardService: OperationGuardService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {
    companion object {
        const val TOOL_NAME = "delete_chat_folder"
        private const val INPUT_SCHEMA = """
        {"type":"object","properties":{"folder_id":{"type":"number"},"confirmed":{"type":"boolean","description":"Required when confirmation mode is enabled"}},"required":["folder_id"]}
        """
    }
    private val log = StructuredLogger.forClass<DeleteChatFolderTool>()

    override fun definition(): McpSchema.Tool = ToolSupport.definition(TOOL_NAME, "Delete a Telegram chat folder without leaving its chats", INPUT_SCHEMA, objectMapper)

    override fun execute(exchange: McpSyncServerExchange, arguments: Map<String, Any>): McpSchema.CallToolResult =
        ToolSupport.execute(TOOL_NAME, arguments, objectMapper, meterRegistry, log, "Failed to delete chat folder", auditService) {
            operationGuardService.checkPermission(TOOL_NAME, arguments)
            val folderId = ChatFolderInputs.requiredPositiveInt(arguments, "folder_id")
            telegramClient.deleteChatFolder(folderId)
            mapOf("folder_id" to folderId, "deleted" to true)
        }
}

/** Reorders the account's chat folders to a caller-supplied complete sequence. */
@Component
class ReorderChatFoldersTool(
    private val telegramClient: TelegramClientService,
    private val operationGuardService: OperationGuardService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {
    companion object {
        const val TOOL_NAME = "reorder_chat_folders"
        private const val INPUT_SCHEMA = """
        {
          "type":"object",
          "properties":{
            "folder_ids":{"type":"array","items":{"type":"number"},"description":"Complete list of folder IDs in the desired order"},
            "main_list_position":{"type":"number","description":"0-based position of the main chat list among the folders (default 0)"}
          },
          "required":["folder_ids"]
        }
        """
    }
    private val log = StructuredLogger.forClass<ReorderChatFoldersTool>()

    override fun definition(): McpSchema.Tool = ToolSupport.definition(
        TOOL_NAME,
        "Reorder Telegram chat folders to the supplied complete ID sequence",
        INPUT_SCHEMA,
        objectMapper,
    )

    override fun execute(exchange: McpSyncServerExchange, arguments: Map<String, Any>): McpSchema.CallToolResult =
        ToolSupport.execute(TOOL_NAME, arguments, objectMapper, meterRegistry, log, "Failed to reorder chat folders", auditService) {
            operationGuardService.checkPermission(TOOL_NAME, arguments)
            val rawIds = arguments["folder_ids"] as? List<*>
                ?: throw InvalidToolInputException("folder_ids is required and must be a list of numbers")
            val folderIds = rawIds.map {
                (it as? Number)?.toInt()
                    ?: it?.toString()?.toIntOrNull()
                    ?: throw InvalidToolInputException("folder_ids entries must be numbers")
            }
            if (folderIds.isEmpty()) throw InvalidToolInputException("folder_ids must not be empty")
            if (folderIds.any { it <= 0 }) throw InvalidToolInputException("folder_ids must be positive")
            val mainListPosition = (arguments["main_list_position"] as? Number)?.toInt() ?: 0
            if (mainListPosition < 0) throw InvalidToolInputException("main_list_position must be non-negative")

            telegramClient.reorderChatFolders(folderIds, mainListPosition)
            mapOf("folder_ids" to folderIds, "main_list_position" to mainListPosition, "reordered" to true)
        }
}

private object ChatFolderInputs {
    fun definition(
        arguments: Map<String, Any>,
        entityResolver: EntityResolverService,
        guardrailService: GuardrailService,
    ): ChatFolderDefinition {
        val title = arguments["title"]?.toString()?.trim().takeUnless { it.isNullOrEmpty() }
            ?: throw InvalidToolInputException("title is required")
        guardrailService.validateInput(title)
        val definition = ChatFolderDefinition(
            title = title,
            iconName = arguments["icon_name"]?.toString()?.trim()?.takeIf { it.isNotEmpty() },
            colorId = optionalInt(arguments, "color_id") ?: 0,
            isShareable = optionalBoolean(arguments, "is_shareable"),
            pinnedChatIds = resolveChatIds(arguments, "pinned_chats", entityResolver),
            includedChatIds = resolveChatIds(arguments, "included_chats", entityResolver),
            excludedChatIds = resolveChatIds(arguments, "excluded_chats", entityResolver),
            excludeMuted = optionalBoolean(arguments, "exclude_muted"),
            excludeRead = optionalBoolean(arguments, "exclude_read"),
            excludeArchived = optionalBoolean(arguments, "exclude_archived"),
            includeContacts = optionalBoolean(arguments, "include_contacts"),
            includeNonContacts = optionalBoolean(arguments, "include_non_contacts"),
            includeBots = optionalBoolean(arguments, "include_bots"),
            includeGroups = optionalBoolean(arguments, "include_groups"),
            includeChannels = optionalBoolean(arguments, "include_channels"),
        )
        validateFolderChatAccess(definition, guardrailService)
        return definition
    }

    fun validateFolderChatAccess(folder: ChatFolderDefinition, guardrailService: GuardrailService) {
        (folder.pinnedChatIds + folder.includedChatIds + folder.excludedChatIds).distinct().forEach(guardrailService::validateChatAccess)
    }

    fun requiredPositiveInt(arguments: Map<String, Any>, name: String): Int =
        optionalPositiveInt(arguments, name) ?: throw InvalidToolInputException("$name is required")

    fun optionalPositiveInt(arguments: Map<String, Any>, name: String): Int? {
        if (arguments[name] == null) return null
        val value = optionalInt(arguments, name) ?: return null
        if (value <= 0) throw InvalidToolInputException("$name must be a positive integer")
        return value
    }

    private fun optionalInt(arguments: Map<String, Any>, name: String): Int? = when (val raw = arguments[name]) {
        null -> null
        is Number -> raw.toInt().takeIf { raw.toDouble() == it.toDouble() }
        else -> raw.toString().trim().toIntOrNull()
    } ?: if (arguments.containsKey(name)) throw InvalidToolInputException("$name must be an integer") else null

    private fun optionalBoolean(arguments: Map<String, Any>, name: String): Boolean = when (val raw = arguments[name]) {
        null -> false
        is Boolean -> raw
        is String -> raw.lowercase().let { value ->
            when (value) {
                "true" -> true
                "false" -> false
                else -> throw InvalidToolInputException("$name must be a boolean")
            }
        }
        else -> throw InvalidToolInputException("$name must be a boolean")
    }

    private fun resolveChatIds(arguments: Map<String, Any>, name: String, entityResolver: EntityResolverService): List<Long> {
        val raw = arguments[name] ?: return emptyList()
        val values = raw as? List<*> ?: throw InvalidToolInputException("$name must be an array")
        return values.mapIndexed { index, identifier ->
            entityResolver.resolve(identifier ?: throw InvalidToolInputException("$name[$index] must be a chat identifier"))
        }.distinct()
    }
}
