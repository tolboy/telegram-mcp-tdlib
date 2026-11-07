package dev.telegrammcp.server.tool.chat

import com.fasterxml.jackson.databind.ObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.exception.InvalidToolInputException
import dev.telegrammcp.server.service.AuditService
import dev.telegrammcp.server.service.EntityResolverService
import dev.telegrammcp.server.service.GuardrailService
import dev.telegrammcp.server.service.OperationGuardService
import dev.telegrammcp.server.tool.McpToolHandler
import dev.telegrammcp.server.tool.ToolSupport
import dev.telegrammcp.server.tool.ToolNumbers
import dev.telegrammcp.server.util.StructuredLogger
import io.micrometer.core.instrument.MeterRegistry
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import org.springframework.stereotype.Component

internal object ModerationArguments {
    const val CHAT_ID_SCHEMA =
        """{ "type": ["string", "integer"], "description": "Chat identifier (ID/@username) of a group or channel you administer" }"""

    fun chatId(entityResolver: EntityResolverService, arguments: Map<String, Any>): Long =
        entityResolver.resolve(arguments["chat_id"] ?: throw InvalidToolInputException("chat_id is required"))

    fun numericId(arguments: Map<String, Any>, key: String): Long {
        val raw = arguments[key] ?: throw InvalidToolInputException("$key is required")
        val value = ToolNumbers.long(raw, key)
        if (value == 0L) throw InvalidToolInputException("$key must not be 0")
        return value
    }
}

/**
 * MCP tool: **list_join_requests** — pending requests to join a chat that
 * requires admin approval.
 */
@Component
class ListJoinRequestsTool(
    private val telegramClient: TelegramClientService,
    private val entityResolver: EntityResolverService,
    private val guardrailService: GuardrailService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {

    private val log = StructuredLogger.forClass<ListJoinRequestsTool>()

    companion object {
        const val TOOL_NAME = "list_join_requests"

        @Suppress("JsonStandardCompliance")
        private val INPUT_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "chat_id": ${ModerationArguments.CHAT_ID_SCHEMA},
            "query": { "type": "string", "description": "Optional filter on the requester's name or username" },
            "invite_link": { "type": "string", "description": "Optional invite link; only requests made through it are returned" },
            "limit": { "type": "integer", "minimum": 1, "maximum": 100, "description": "Max requests to return (default 50, max 100)" }
          },
          "required": ["chat_id"]
        }
        """.trimIndent()
    }

    override fun definition(): McpSchema.Tool = ToolSupport.definition(
        TOOL_NAME,
        "List pending requests to join a Telegram group or channel (admin privileges required)",
        INPUT_SCHEMA,
        objectMapper,
    )

    override fun execute(exchange: McpSyncServerExchange, arguments: Map<String, Any>): McpSchema.CallToolResult =
        ToolSupport.execute(TOOL_NAME, arguments, objectMapper, meterRegistry, log, "Failed to list join requests", auditService) {
            val chatId = ModerationArguments.chatId(entityResolver, arguments)
            val limit = if (arguments.containsKey("limit")) ToolNumbers.int(arguments["limit"], "limit") else 50
            if (limit !in 1..100) throw InvalidToolInputException("limit must be between 1 and 100")
            val query = arguments["query"]?.toString()?.trim().orEmpty()
            val inviteLink = arguments["invite_link"]?.toString()?.trim().orEmpty()
            guardrailService.validateChatAccess(chatId)
            // Same input filter as the other search tools.
            listOf(query, inviteLink).filter(String::isNotEmpty).forEach(guardrailService::validateInput)
            log.withTool(TOOL_NAME).info("Listing join requests for chat {}", chatId)
            telegramClient.getChatJoinRequests(chatId, query, inviteLink, limit)
        }
}

/**
 * MCP tool: **process_join_request** — approves or declines one pending join
 * request. Declining is not destructive: the user can ask again.
 */
@Component
class ProcessJoinRequestTool(
    private val telegramClient: TelegramClientService,
    private val entityResolver: EntityResolverService,
    private val guardrailService: GuardrailService,
    private val operationGuardService: OperationGuardService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {

    private val log = StructuredLogger.forClass<ProcessJoinRequestTool>()

    companion object {
        const val TOOL_NAME = "process_join_request"

        @Suppress("JsonStandardCompliance")
        private val INPUT_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "chat_id": ${ModerationArguments.CHAT_ID_SCHEMA},
            "user_id": { "type": ["string", "integer"], "description": "Numeric user ID from list_join_requests" },
            "action": { "type": "string", "enum": ["approve", "decline"], "description": "approve adds the user to the chat; decline rejects the request" }
          },
          "required": ["chat_id", "user_id", "action"]
        }
        """.trimIndent()
    }

    override fun definition(): McpSchema.Tool = ToolSupport.definition(
        TOOL_NAME,
        "Approve or decline one pending request to join a Telegram group or channel",
        INPUT_SCHEMA,
        objectMapper,
    )

    override fun execute(exchange: McpSyncServerExchange, arguments: Map<String, Any>): McpSchema.CallToolResult =
        ToolSupport.execute(TOOL_NAME, arguments, objectMapper, meterRegistry, log, "Failed to process join request", auditService) {
            operationGuardService.checkPermission(TOOL_NAME, arguments)
            val chatId = ModerationArguments.chatId(entityResolver, arguments)
            val userId = ModerationArguments.numericId(arguments, "user_id")
            if (userId < 0) throw InvalidToolInputException("user_id must be a positive Telegram user ID")
            val approve = when (arguments["action"]?.toString()?.trim()?.lowercase()) {
                "approve" -> true
                "decline" -> false
                else -> throw InvalidToolInputException("action must be approve or decline")
            }
            guardrailService.validateChatAccess(chatId)
            log.withTool(TOOL_NAME).info("{} join request of user {} in chat {}",
                if (approve) "Approving" else "Declining", userId, chatId)
            telegramClient.processChatJoinRequest(chatId, userId, approve)
            mapOf("chat_id" to chatId, "user_id" to userId, "approved" to approve, "processed" to true)
        }
}

/**
 * MCP tool: **delete_messages_by_sender** — deletes every message one sender
 * posted in a supergroup. Destructive and confirmation-gated.
 */
@Component
class DeleteMessagesBySenderTool(
    private val telegramClient: TelegramClientService,
    private val entityResolver: EntityResolverService,
    private val guardrailService: GuardrailService,
    private val operationGuardService: OperationGuardService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {

    private val log = StructuredLogger.forClass<DeleteMessagesBySenderTool>()

    companion object {
        const val TOOL_NAME = "delete_messages_by_sender"

        @Suppress("JsonStandardCompliance")
        private val INPUT_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "chat_id": ${ModerationArguments.CHAT_ID_SCHEMA},
            "sender_id": { "type": ["string", "integer"], "description": "Numeric sender ID: a user ID, or a negative chat ID for messages sent on behalf of a chat" },
            "confirmed": { "type": "boolean", "description": "Set to true to confirm this destructive operation" }
          },
          "required": ["chat_id", "sender_id"]
        }
        """.trimIndent()
    }

    override fun definition(): McpSchema.Tool = ToolSupport.definition(
        TOOL_NAME,
        "Delete all messages from one sender in a Telegram supergroup (destructive, requires confirmation and admin rights)",
        INPUT_SCHEMA,
        objectMapper,
    )

    override fun execute(exchange: McpSyncServerExchange, arguments: Map<String, Any>): McpSchema.CallToolResult =
        ToolSupport.execute(TOOL_NAME, arguments, objectMapper, meterRegistry, log, "Failed to delete messages by sender", auditService) {
            operationGuardService.checkPermission(TOOL_NAME, arguments)
            val chatId = ModerationArguments.chatId(entityResolver, arguments)
            val senderId = ModerationArguments.numericId(arguments, "sender_id")
            guardrailService.validateChatAccess(chatId)
            log.withTool(TOOL_NAME).info("Deleting all messages from sender {} in chat {}", senderId, chatId)
            telegramClient.deleteMessagesBySender(chatId, senderId)
            mapOf("chat_id" to chatId, "sender_id" to senderId, "deleted" to true)
        }
}

/**
 * MCP tool: **get_chat_statistics** — summary statistics of a supergroup or
 * channel (Telegram exposes them to admins of larger chats only).
 */
@Component
class GetChatStatisticsTool(
    private val telegramClient: TelegramClientService,
    private val entityResolver: EntityResolverService,
    private val guardrailService: GuardrailService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {

    private val log = StructuredLogger.forClass<GetChatStatisticsTool>()

    companion object {
        const val TOOL_NAME = "get_chat_statistics"

        @Suppress("JsonStandardCompliance")
        private val INPUT_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "chat_id": ${ModerationArguments.CHAT_ID_SCHEMA}
          },
          "required": ["chat_id"]
        }
        """.trimIndent()
    }

    override fun definition(): McpSchema.Tool = ToolSupport.definition(
        TOOL_NAME,
        "Get summary statistics (members, messages, views, top senders) for a Telegram supergroup or channel you administer; graphs are not included",
        INPUT_SCHEMA,
        objectMapper,
    )

    override fun execute(exchange: McpSyncServerExchange, arguments: Map<String, Any>): McpSchema.CallToolResult =
        ToolSupport.execute(TOOL_NAME, arguments, objectMapper, meterRegistry, log, "Failed to get chat statistics", auditService) {
            val chatId = ModerationArguments.chatId(entityResolver, arguments)
            guardrailService.validateChatAccess(chatId)
            log.withTool(TOOL_NAME).info("Getting statistics for chat {}", chatId)
            telegramClient.getChatStatistics(chatId)
        }
}
