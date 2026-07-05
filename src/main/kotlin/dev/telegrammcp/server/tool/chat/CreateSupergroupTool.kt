package dev.telegrammcp.server.tool.chat

import com.fasterxml.jackson.databind.ObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.exception.InvalidToolInputException
import dev.telegrammcp.server.model.ChatType
import dev.telegrammcp.server.service.AuditService
import dev.telegrammcp.server.service.FileSecurityService
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
 * MCP tool: **create_supergroup**
 *
 * Composite tool that creates a private Telegram supergroup with optional
 * forum topics and chat photo in a single call. Designed to replace the
 * brittle "call create_channel, then poll, then enable forum, then upload
 * photo" sequence that can otherwise cause a host-side reconciliation loop.
 *
 * Behaviour:
 *  1. Creates a supergroup (`isChannel=false`, `isForum=enable_forum_topics`).
 *  2. Re-fetches the chat through `getChat` until TDLib's local cache
 *     reports the expected supergroup type — this handles the brief window
 *     where TDLib returns a stale type after creation.
 *  3. If forum topics didn't stick on creation, calls
 *     `toggleSupergroupIsForum` explicitly.
 *  4. Optionally uploads a photo from a local path (validated through
 *     [FileSecurityService]).
 *  5. Optionally pre-creates an ordered list of forum topics. Each topic
 *     failure is reported but does not abort the whole call.
 *
 * The whole flow runs under a single anti-spam check (against
 * `create_supergroup`) and a single confirmation gate, instead of N
 * separate tool invocations each consuming budget.
 */
@Component
class CreateSupergroupTool(
    private val telegramClient: TelegramClientService,
    private val guardrailService: GuardrailService,
    private val operationGuardService: OperationGuardService,
    private val fileSecurityService: FileSecurityService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {

    private val log = StructuredLogger.forClass<CreateSupergroupTool>()

    companion object {
        const val TOOL_NAME = "create_supergroup"
        private const val MAX_TITLE = 128
        private const val MAX_DESCRIPTION = 255
        private const val MAX_TOPIC_NAME = 128
        private const val MAX_TOPICS = 32
        private val ALLOWED_ICON_COLORS = setOf(
            0x6FB9F0, 0xFFD67E, 0xCB86DB, 0x8EEE98, 0xFF93B2, 0xFB6F5F,
        )

        @Suppress("JsonStandardCompliance")
        private val INPUT_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "title": {
              "type": "string",
              "description": "Supergroup title (1-128 chars)"
            },
            "description": {
              "type": "string",
              "description": "Description (optional, max 255 chars)"
            },
            "enable_forum_topics": {
              "type": "boolean",
              "description": "Whether to enable forum topics (default true)"
            },
            "topics": {
              "type": "array",
              "description": "Optional ordered list of forum topics to pre-create",
              "items": {
                "type": "object",
                "properties": {
                  "name": { "type": "string", "description": "Topic name (1-128 chars)" },
                  "icon_color": {
                    "type": ["string", "number"],
                    "description": "Optional icon color (one of 0x6FB9F0, 0xFFD67E, 0xCB86DB, 0x8EEE98, 0xFF93B2, 0xFB6F5F)"
                  },
                  "custom_emoji_id": { "type": ["string", "number"], "description": "Optional custom emoji id" }
                },
                "required": ["name"]
              }
            },
            "photo_path": {
              "type": "string",
              "description": "Optional local image path (JPEG/PNG) to set as chat photo"
            },
            "confirmed": {
              "type": "boolean",
              "description": "Set to true to confirm this destructive operation"
            }
          },
          "required": ["title"]
        }
        """.trimIndent()
    }

    override fun definition(): McpSchema.Tool = ToolSupport.definition(
        TOOL_NAME,
        "Create a private Telegram supergroup with optional forum topics and photo (atomic; requires confirmation)",
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
        failureMessage = "Failed to create supergroup",
        auditService = auditService,
    ) {
        operationGuardService.checkPermission(TOOL_NAME, arguments)

        val title = arguments["title"]?.toString()?.takeIf { it.isNotBlank() }
            ?: throw InvalidToolInputException("title is required")
        if (title.length > MAX_TITLE) {
            throw InvalidToolInputException("title must be $MAX_TITLE characters or fewer")
        }

        val description = arguments["description"]?.toString().orEmpty()
        if (description.length > MAX_DESCRIPTION) {
            throw InvalidToolInputException("description must be $MAX_DESCRIPTION characters or fewer")
        }

        val enableForumTopics = arguments["enable_forum_topics"]?.toString()?.toBoolean() ?: true
        val topics = parseTopics(arguments["topics"])
        val photoPath = arguments["photo_path"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }

        guardrailService.validateInput(title)
        if (description.isNotBlank()) guardrailService.validateInput(description)
        topics.forEach { guardrailService.validateInput(it.name) }

        val validatedPhoto = photoPath?.let { fileSecurityService.validateForUpload(it) }

        log.withTool(TOOL_NAME).info(
            "Creating supergroup '{}' (forum={}, topics={}, photo={})",
            title, enableForumTopics, topics.size, validatedPhoto != null,
        )

        // 1. Create + verify type. createSupergroupOrChannel re-polls getChat
        //    internally so the returned ChatInfo is authoritative.
        val chat = telegramClient.createSupergroupOrChannel(
            title = title,
            description = description,
            isSupergroup = true,
            isForum = enableForumTopics,
        )

        val warnings = mutableListOf<String>()
        if (chat.type != ChatType.SUPERGROUP) {
            throw IllegalStateException(
                "Telegram created chat ${chat.chatId} with type=${chat.type}; expected SUPERGROUP. " +
                    "The chat was not configured with photo or topics.",
            )
        }

        // 2. createSupergroupOrChannel creates a plain supergroup first,
        //    verifies its type, then enables forum mode centrally when
        //    requested. Do not toggle again here: a host may also verify
        //    the chat, and duplicate toggles can trip Telegram anti-spam.
        var forumEnabled = enableForumTopics

        // 3. Optional photo upload (best-effort).
        var photoSet = false
        if (validatedPhoto != null) {
            runCatching {
                telegramClient.setChatPhoto(chat.chatId, validatedPhoto.toString())
            }.onSuccess { photoSet = it }
                .onFailure { error ->
                    warnings.add("edit_chat_photo failed: ${error.message}")
                }
        }

        // 4. Pre-create topics (best-effort each).
        val createdTopics = mutableListOf<Map<String, Any?>>()
        if (forumEnabled && topics.isNotEmpty()) {
            for (spec in topics) {
                runCatching {
                    telegramClient.createForumTopic(
                        chatId = chat.chatId,
                        name = spec.name,
                        iconColor = spec.iconColor,
                        customEmojiId = spec.customEmojiId,
                    )
                }.onSuccess { topic ->
                    createdTopics.add(
                        mapOf(
                            "name" to topic.name,
                            "topic_id" to topic.topicId,
                            "message_thread_id" to topic.messageThreadId,
                            "icon_color" to topic.iconColor,
                            "custom_emoji_id" to topic.customEmojiId,
                        ),
                    )
                }.onFailure { error ->
                    warnings.add("create_topic '${spec.name}' failed: ${error.message}")
                }
            }
        }

        linkedMapOf<String, Any?>(
            "chat_id" to chat.chatId,
            "title" to chat.title,
            "type" to chat.type.name,
            "description" to chat.description,
            "member_count" to chat.memberCount,
            "forum_topics_enabled" to forumEnabled,
            "photo_set" to photoSet,
            "topics" to createdTopics,
            "warnings" to warnings,
        )
    }

    private fun parseTopics(raw: Any?): List<TopicSpec> {
        if (raw == null) return emptyList()
        val list = raw as? List<*>
            ?: throw InvalidToolInputException("topics must be an array")
        if (list.size > MAX_TOPICS) {
            throw InvalidToolInputException("topics must contain at most $MAX_TOPICS entries")
        }
        return list.mapIndexed { index, item ->
            @Suppress("UNCHECKED_CAST")
            val map = item as? Map<String, Any?>
                ?: throw InvalidToolInputException("topics[$index] must be an object")
            val name = map["name"]?.toString()?.trim()
                ?: throw InvalidToolInputException("topics[$index].name is required")
            if (name.isEmpty()) {
                throw InvalidToolInputException("topics[$index].name must not be blank")
            }
            if (name.length > MAX_TOPIC_NAME) {
                throw InvalidToolInputException("topics[$index].name must be $MAX_TOPIC_NAME chars or fewer")
            }
            val iconColor = parseIconColor(map["icon_color"], index)
            if (iconColor != null && iconColor !in ALLOWED_ICON_COLORS) {
                throw InvalidToolInputException(
                    "topics[$index].icon_color must be one of " +
                        ALLOWED_ICON_COLORS.joinToString { "0x${it.toString(16).uppercase()}" },
                )
            }
            val customEmojiId = parseLong(map["custom_emoji_id"], "topics[$index].custom_emoji_id")
            TopicSpec(name, iconColor, customEmojiId)
        }
    }

    private fun parseIconColor(raw: Any?, index: Int): Int? {
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
                    throw InvalidToolInputException("topics[$index].icon_color must be an integer or hex string")
                }
            }
            else -> throw InvalidToolInputException("topics[$index].icon_color must be an integer or hex string")
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

    private data class TopicSpec(
        val name: String,
        val iconColor: Int?,
        val customEmojiId: Long?,
    )
}
