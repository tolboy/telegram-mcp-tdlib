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
import dev.telegrammcp.server.util.StructuredLogger
import io.micrometer.core.instrument.MeterRegistry
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import org.springframework.stereotype.Component

/**
 * MCP tool: **list_invite_links** — lists invite links the current account
 * created for a chat (admin privileges required).
 */
@Component
class ListInviteLinksTool(
    private val telegramClient: TelegramClientService,
    private val entityResolver: EntityResolverService,
    private val guardrailService: GuardrailService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {

    private val log = StructuredLogger.forClass<ListInviteLinksTool>()

    companion object {
        const val TOOL_NAME = "list_invite_links"

        @Suppress("JsonStandardCompliance")
        private val INPUT_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "chat_id": { "type": ["string", "number"], "description": "Chat identifier (ID/@username)" },
            "include_revoked": { "type": "boolean", "description": "List revoked links instead of active ones (default false)" },
            "limit": { "type": "number", "description": "Max links to return (default 20, max 100)" }
          },
          "required": ["chat_id"]
        }
        """.trimIndent()
    }

    override fun definition(): McpSchema.Tool = ToolSupport.definition(
        TOOL_NAME,
        "List invite links created by the current account for a Telegram chat",
        INPUT_SCHEMA,
        objectMapper,
    )

    override fun execute(exchange: McpSyncServerExchange, arguments: Map<String, Any>): McpSchema.CallToolResult =
        ToolSupport.execute(TOOL_NAME, arguments, objectMapper, meterRegistry, log, "Failed to list invite links", auditService) {
            val chatId = entityResolver.resolve(
                arguments["chat_id"] ?: throw InvalidToolInputException("chat_id is required"),
            )
            val includeRevoked = arguments["include_revoked"]?.toString()?.toBoolean() ?: false
            val limit = ((arguments["limit"] as? Number)?.toInt() ?: 20).coerceIn(1, 100)

            guardrailService.validateChatAccess(chatId)
            log.withTool(TOOL_NAME).info("Listing invite links of chat {} (revoked={}, limit={})", chatId, includeRevoked, limit)
            mapOf(
                "chat_id" to chatId,
                "include_revoked" to includeRevoked,
                "invite_links" to telegramClient.getChatInviteLinks(chatId, includeRevoked, limit),
            )
        }
}

/**
 * MCP tool: **revoke_invite_link** — revokes an invite link so it can no
 * longer be used to join the chat (destructive, confirmation-gated).
 */
@Component
class RevokeInviteLinkTool(
    private val telegramClient: TelegramClientService,
    private val entityResolver: EntityResolverService,
    private val guardrailService: GuardrailService,
    private val operationGuardService: OperationGuardService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {

    private val log = StructuredLogger.forClass<RevokeInviteLinkTool>()

    companion object {
        const val TOOL_NAME = "revoke_invite_link"

        @Suppress("JsonStandardCompliance")
        private val INPUT_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "chat_id": { "type": ["string", "number"], "description": "Chat identifier (ID/@username)" },
            "invite_link": { "type": "string", "description": "The exact invite link to revoke" },
            "confirmed": { "type": "boolean", "description": "Required when confirmation mode is enabled" }
          },
          "required": ["chat_id", "invite_link"]
        }
        """.trimIndent()
    }

    override fun definition(): McpSchema.Tool = ToolSupport.definition(
        TOOL_NAME,
        "Revoke a Telegram chat invite link so it can no longer be used to join",
        INPUT_SCHEMA,
        objectMapper,
    )

    override fun execute(exchange: McpSyncServerExchange, arguments: Map<String, Any>): McpSchema.CallToolResult =
        ToolSupport.execute(TOOL_NAME, arguments, objectMapper, meterRegistry, log, "Failed to revoke invite link", auditService) {
            operationGuardService.checkPermission(TOOL_NAME, arguments)

            val chatId = entityResolver.resolve(
                arguments["chat_id"] ?: throw InvalidToolInputException("chat_id is required"),
            )
            val inviteLink = arguments["invite_link"]?.toString()?.takeIf { it.isNotBlank() }
                ?: throw InvalidToolInputException("invite_link is required")

            guardrailService.validateChatAccess(chatId)
            log.withTool(TOOL_NAME).info("Revoking an invite link of chat {}", chatId)
            mapOf(
                "chat_id" to chatId,
                "revoked" to true,
                "invite_links" to telegramClient.revokeChatInviteLink(chatId, inviteLink),
            )
        }
}
