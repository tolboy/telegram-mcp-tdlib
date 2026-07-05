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
 * MCP tool: **create_topic**
 *
 * Creates a forum topic in a supergroup with topics enabled.
 */
@Component
class CreateTopicTool(
    private val telegramClient: TelegramClientService,
    private val entityResolver: EntityResolverService,
    private val guardrailService: GuardrailService,
    private val operationGuardService: OperationGuardService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {

    private val log = StructuredLogger.forClass<CreateTopicTool>()

    companion object {
        const val TOOL_NAME = "create_topic"
        private const val MAX_NAME = 128
        private val ALLOWED_ICON_COLORS = setOf(
            0x6FB9F0,
            0xFFD67E,
            0xCB86DB,
            0x8EEE98,
            0xFF93B2,
            0xFB6F5F,
        )

        @Suppress("JsonStandardCompliance")
        private val INPUT_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "chat_id": {
              "type": ["string", "number"],
              "description": "Forum-enabled supergroup chat identifier (ID/@username/+phone)"
            },
            "name": {
              "type": "string",
              "description": "Topic name (1-128 chars)"
            },
            "icon_color": {
              "type": ["string", "number"],
              "description": "Optional topic icon color. Allowed: 0x6FB9F0, 0xFFD67E, 0xCB86DB, 0x8EEE98, 0xFF93B2, 0xFB6F5F"
            },
            "custom_emoji_id": {
              "type": ["string", "number"],
              "description": "Optional custom emoji ID for the topic icon"
            }
          },
          "required": ["chat_id", "name"]
        }
        """.trimIndent()
    }

    override fun definition(): McpSchema.Tool = ToolSupport.definition(
        TOOL_NAME,
        "Create a forum topic in a Telegram supergroup with topics enabled",
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
        failureMessage = "Failed to create forum topic",
        auditService = auditService,
    ) {
        operationGuardService.checkPermission(TOOL_NAME, arguments)

        val name = arguments["name"]?.toString()?.trim()
            ?: throw InvalidToolInputException("name is required")
        if (name.isBlank()) throw InvalidToolInputException("name must not be blank")
        if (name.length > MAX_NAME) {
            throw InvalidToolInputException("name must be $MAX_NAME characters or fewer")
        }

        val chatId = entityResolver.resolve(
            arguments["chat_id"] ?: throw InvalidToolInputException("chat_id is required"),
        )
        val iconColor = parseIconColor(arguments["icon_color"])
        if (iconColor != null && iconColor !in ALLOWED_ICON_COLORS) {
            throw InvalidToolInputException(
                "icon_color must be one of ${ALLOWED_ICON_COLORS.joinToString { "0x${it.toString(16).uppercase()}" }}",
            )
        }
        val customEmojiId = parseLong(arguments["custom_emoji_id"], "custom_emoji_id")

        guardrailService.validateInput(name)
        guardrailService.validateChatAccess(chatId)
        log.withTool(TOOL_NAME).info("Creating forum topic '{}' in chat {}", name, chatId)

        telegramClient.createForumTopic(chatId, name, iconColor, customEmojiId)
    }

    private fun parseIconColor(raw: Any?): Int? {
        if (raw == null) return null
        return when (raw) {
            is Number -> raw.toInt()
            is String -> {
                val value = raw.trim()
                if (value.isBlank()) return null
                runCatching {
                    if (value.startsWith("0x", ignoreCase = true)) {
                        value.removePrefix("0x").removePrefix("0X").toInt(16)
                    } else {
                        value.toInt()
                    }
                }.getOrElse {
                    throw InvalidToolInputException("icon_color must be an integer or hex string")
                }
            }
            else -> throw InvalidToolInputException("icon_color must be an integer or hex string")
        }
    }

    private fun parseLong(raw: Any?, field: String): Long? {
        if (raw == null) return null
        return when (raw) {
            is Number -> raw.toLong()
            is String -> raw.trim().takeIf { it.isNotBlank() }?.toLongOrNull()
                ?: throw InvalidToolInputException("$field must be an integer")
            else -> throw InvalidToolInputException("$field must be an integer")
        }
    }
}
