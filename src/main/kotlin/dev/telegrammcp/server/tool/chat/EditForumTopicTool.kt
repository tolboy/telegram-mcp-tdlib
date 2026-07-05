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
 * MCP tool: **edit_forum_topic**
 *
 * Edits a forum topic title and, optionally, its custom emoji icon.
 */
@Component
class EditForumTopicTool(
    private val telegramClient: TelegramClientService,
    private val entityResolver: EntityResolverService,
    private val guardrailService: GuardrailService,
    private val operationGuardService: OperationGuardService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {

    private val log = StructuredLogger.forClass<EditForumTopicTool>()

    companion object {
        const val TOOL_NAME = "edit_forum_topic"
        private const val MAX_NAME = 128

        @Suppress("JsonStandardCompliance")
        private val INPUT_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "chat_id": {
              "type": ["string", "number"],
              "description": "Forum-enabled supergroup chat identifier (ID/@username/+phone)"
            },
            "message_thread_id": {
              "type": ["string", "number"],
              "description": "Forum topic message thread ID"
            },
            "topic_id": {
              "type": ["string", "number"],
              "description": "Alias for message_thread_id"
            },
            "thread_id": {
              "type": ["string", "number"],
              "description": "Alias for message_thread_id"
            },
            "name": {
              "type": "string",
              "description": "New topic name (1-128 chars)"
            },
            "title": {
              "type": "string",
              "description": "Alias for name"
            },
            "custom_emoji_id": {
              "type": ["string", "number"],
              "description": "Optional new custom emoji ID for the topic icon"
            },
            "edit_icon_custom_emoji": {
              "type": "boolean",
              "description": "Whether to update the topic icon; defaults to true when custom_emoji_id or remove_custom_emoji is supplied"
            },
            "remove_custom_emoji": {
              "type": "boolean",
              "description": "Remove the current custom emoji icon"
            }
          },
          "required": ["chat_id"]
        }
        """.trimIndent()
    }

    override fun definition(): McpSchema.Tool = ToolSupport.definition(
        TOOL_NAME,
        "Edit a forum topic title or custom emoji icon in a Telegram supergroup",
        INPUT_SCHEMA,
        objectMapper,
    )

    override fun execute(
        exchange: McpSyncServerExchange,
        arguments: Map<String, Any>,
    ): McpSchema.CallToolResult = ToolSupport.execute(
        toolName = TOOL_NAME,
        arguments = arguments,
        objectMapper = objectMapper,
        meterRegistry = meterRegistry,
        log = log,
        failureMessage = "Failed to edit forum topic",
        auditService = auditService,
    ) {
        operationGuardService.checkPermission(TOOL_NAME, arguments)

        val chatId = entityResolver.resolve(
            arguments["chat_id"] ?: throw InvalidToolInputException("chat_id is required"),
        )
        val messageThreadId = parseForumTopicThreadId(arguments)
        val name = parseTopicName(arguments)
        val removeCustomEmoji = parseBoolean(arguments["remove_custom_emoji"], default = false)
        val customEmojiId = parseOptionalLong(arguments["custom_emoji_id"], "custom_emoji_id")
        val editIconCustomEmoji = parseBoolean(
            arguments["edit_icon_custom_emoji"],
            default = removeCustomEmoji || arguments.containsKey("custom_emoji_id"),
        )

        if (name == null && !editIconCustomEmoji) {
            throw InvalidToolInputException("name/title or icon update is required")
        }

        name?.let { guardrailService.validateInput(it) }
        guardrailService.validateChatAccess(chatId)
        log.withTool(TOOL_NAME).info(
            "Editing forum topic {} in chat {} (nameSet={}, editIconCustomEmoji={})",
            messageThreadId,
            chatId,
            name != null,
            editIconCustomEmoji,
        )

        mapOf(
            "chatId" to chatId,
            "messageThreadId" to messageThreadId,
            "name" to name,
            "updated" to telegramClient.editForumTopic(
                chatId = chatId,
                messageThreadId = messageThreadId,
                name = name,
                editIconCustomEmoji = editIconCustomEmoji,
                customEmojiId = if (removeCustomEmoji) null else customEmojiId,
            ),
        )
    }

    private fun parseTopicName(arguments: Map<String, Any>): String? {
        val raw = arguments["name"] ?: arguments["title"] ?: return null
        val value = raw.toString().trim()
        if (value.isBlank()) throw InvalidToolInputException("name must not be blank")
        if (value.length > MAX_NAME) {
            throw InvalidToolInputException("name must be $MAX_NAME characters or fewer")
        }
        return value
    }
}
