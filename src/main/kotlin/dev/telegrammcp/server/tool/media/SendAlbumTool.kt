package dev.telegrammcp.server.tool.media

import com.fasterxml.jackson.databind.ObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.exception.InvalidToolInputException
import dev.telegrammcp.server.model.AlbumItem
import dev.telegrammcp.server.model.AlbumItemType
import dev.telegrammcp.server.service.AuditService
import dev.telegrammcp.server.service.EntityResolverService
import dev.telegrammcp.server.service.FileSecurityService
import dev.telegrammcp.server.service.GuardrailService
import dev.telegrammcp.server.service.OperationGuardService
import dev.telegrammcp.server.service.SendOperationService
import dev.telegrammcp.server.tool.McpToolHandler
import dev.telegrammcp.server.tool.RecoverableSend
import dev.telegrammcp.server.tool.ToolSupport
import dev.telegrammcp.server.util.StructuredLogger
import io.micrometer.core.instrument.MeterRegistry
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import org.springframework.stereotype.Component

/**
 * MCP tool: **send_album**
 *
 * Uploads 2-10 local files and sends them as one grouped media album. Every
 * file passes the same upload checks as `send_file`.
 */
@Component
class SendAlbumTool(
    private val telegramClient: TelegramClientService,
    private val entityResolver: EntityResolverService,
    private val guardrailService: GuardrailService,
    private val fileSecurityService: FileSecurityService,
    private val operationGuardService: OperationGuardService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
    private val sendOperations: SendOperationService? = null,
) : McpToolHandler {

    private val log = StructuredLogger.forClass<SendAlbumTool>()

    companion object {
        const val TOOL_NAME = "send_album"
        const val MIN_ITEMS = 2
        const val MAX_ITEMS = 10
        private const val MAX_CAPTION_LENGTH = 4096

        @Suppress("JsonStandardCompliance")
        private val INPUT_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "chat_id": {
              "type": ["string", "number"],
              "description": "Chat identifier: numeric ID, @username, +phone, or the canonical value self"
            },
            "items": {
              "type": "array",
              "minItems": $MIN_ITEMS,
              "maxItems": $MAX_ITEMS,
              "description": "2-10 album items in display order. Photos and videos can be mixed; documents and audio files must each form their own album",
              "items": {
                "type": "object",
                "properties": {
                  "file_path": { "type": "string", "description": "Local file path" },
                  "type": { "type": "string", "enum": ["photo", "video", "document", "audio"], "description": "How Telegram should present the file" },
                  "caption": { "type": "string", "description": "Optional caption for this item" }
                },
                "required": ["file_path", "type"]
              }
            }
          },
          "required": ["chat_id", "items"]
        }
        """.trimIndent()

        /** Telegram's grouping rule; returns a message describing the violation, or null. */
        fun groupingError(types: Collection<AlbumItemType>): String? {
            val kinds = types.toSet()
            return when {
                kinds.size <= 1 -> null
                kinds.all { it == AlbumItemType.PHOTO || it == AlbumItemType.VIDEO } -> null
                else -> "an album can mix photos and videos, but documents and audio files must each form their own album"
            }
        }
    }

    override fun definition(): McpSchema.Tool = ToolSupport.definition(
        TOOL_NAME,
        "Send 2-10 local files to a Telegram chat as one grouped media album (write, file security)",
        INPUT_SCHEMA,
        objectMapper,
    )

    override fun execute(exchange: McpSyncServerExchange, arguments: Map<String, Any>): McpSchema.CallToolResult =
        ToolSupport.execute(TOOL_NAME, arguments, objectMapper, meterRegistry, log, "Failed to send album", auditService) {
            RecoverableSend.check(operationGuardService, TOOL_NAME, arguments)
            val chatId = entityResolver.resolve(
                arguments["chat_id"] ?: throw InvalidToolInputException("chat_id is required"),
            )
            val rawItems = arguments["items"] as? List<*>
                ?: throw InvalidToolInputException("items is required and must be an array")
            if (rawItems.size !in MIN_ITEMS..MAX_ITEMS) {
                throw InvalidToolInputException("items must contain $MIN_ITEMS-$MAX_ITEMS files")
            }
            val parsed = rawItems.mapIndexed { index, raw -> parseItem(index, raw) }
            groupingError(parsed.map { it.type })?.let { throw InvalidToolInputException(it) }
            // Validate every file before anything is uploaded.
            val validated = parsed.map { it to fileSecurityService.validateForUpload(it.filePath) }
            val items = validated.map { (item, path) -> item.copy(filePath = path.toString()) }

            guardrailService.validateChatAccess(chatId)
            log.withTool(TOOL_NAME).info("Sending album of {} items to chat {}", items.size, chatId)
            val canonical = validated.map { (item, path) ->
                listOf(path.toString(), item.type.name, item.caption, RecoverableSend.fileFingerprint(arguments, path))
            }
            RecoverableSend.execute(sendOperations, operationGuardService, TOOL_NAME, arguments, chatId, canonical,
                expectedCount = items.size) {
                telegramClient.sendAlbum(chatId, items)
            }
        }

    private fun parseItem(index: Int, raw: Any?): AlbumItem {
        val item = raw as? Map<*, *> ?: throw InvalidToolInputException("items[$index] must be an object")
        val path = item["file_path"]?.toString()?.takeIf { it.isNotBlank() }
            ?: throw InvalidToolInputException("items[$index].file_path is required")
        val typeName = item["type"]?.toString()?.trim()?.lowercase()
            ?: throw InvalidToolInputException("items[$index].type is required")
        val type = AlbumItemType.entries.firstOrNull { it.name.lowercase() == typeName }
            ?: throw InvalidToolInputException("items[$index].type must be photo, video, document or audio")
        val caption = item["caption"]?.toString()?.takeIf { it.isNotEmpty() }
        if (caption != null && caption.length > MAX_CAPTION_LENGTH) {
            throw InvalidToolInputException("items[$index].caption must be at most $MAX_CAPTION_LENGTH characters")
        }
        return AlbumItem(path, type, caption)
    }
}
