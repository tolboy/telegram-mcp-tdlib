package dev.telegrammcp.server.tool.media

import com.fasterxml.jackson.databind.ObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.exception.InvalidToolInputException
import dev.telegrammcp.server.service.AuditService
import dev.telegrammcp.server.service.EntityResolverService
import dev.telegrammcp.server.service.GuardrailService
import dev.telegrammcp.server.model.AuditOutcome
import dev.telegrammcp.server.tool.McpToolHandler
import dev.telegrammcp.server.util.StructuredLogger
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import org.springframework.stereotype.Component

/**
 * MCP tool: **download_media**
 *
 * Downloads media from a Telegram message. The file is saved locally
 * by TDLib and the local path is returned.
 *
 * File security: download destination is validated against allowed roots
 * when `file_path` is specified; otherwise TDLib default cache is used.
 */
@Component
class DownloadMediaTool(
    private val telegramClient: TelegramClientService,
    private val entityResolver: EntityResolverService,
    private val guardrailService: GuardrailService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {

    private val log = StructuredLogger.forClass<DownloadMediaTool>()

    companion object {
        const val TOOL_NAME = "download_media"

        @Suppress("JsonStandardCompliance")
        private val INPUT_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "chat_id": {
              "type": ["string", "number"],
              "description": "Chat identifier: numeric ID, @username, +phone, or the canonical value self"
            },
            "message_id": {
              "type": "number",
              "description": "ID of the message containing media to download"
            }
          },
          "required": ["chat_id", "message_id"]
        }
        """.trimIndent()
    }

    override fun definition(): McpSchema.Tool =
        dev.telegrammcp.server.tool.ToolSupport.definition(TOOL_NAME, "Download media from a Telegram message to a local file", INPUT_SCHEMA, objectMapper)

    override fun execute(
        exchange: McpSyncServerExchange,
        arguments: Map<String, Any>,
    ): McpSchema.CallToolResult {
        val sample = Timer.start(meterRegistry)
        val startMs = System.currentTimeMillis()

        return try {
            val chatId = resolveChatId(arguments)
            val messageId = extractMessageId(arguments)

            log.withTool(TOOL_NAME).info("Downloading media from message {} in chat {}", messageId, chatId)

            guardrailService.validateChatAccess(chatId)

            val result = telegramClient.downloadMedia(chatId, messageId)
            val json = objectMapper.writeValueAsString(result)

            auditService.record(TOOL_NAME, arguments, AuditOutcome.SUCCESS, durationMs = System.currentTimeMillis() - startMs)
            dev.telegrammcp.server.tool.ToolSupport.textResult(json)
        } catch (ex: Exception) {
            log.withTool(TOOL_NAME).error("Failed to download media: {}", ex.message, ex)
            auditService.record(TOOL_NAME, arguments, AuditOutcome.ERROR, error = ex.message)
            dev.telegrammcp.server.tool.ToolSupport.errorText("Error: ${ex.message}")
        } finally {
            sample.stop(Timer.builder("mcp.tool.execution").tag("tool", TOOL_NAME).register(meterRegistry))
        }
    }

    private fun resolveChatId(args: Map<String, Any>): Long {
        val raw = args["chat_id"] ?: throw InvalidToolInputException("chat_id is required")
        return entityResolver.resolve(raw)
    }

    private fun extractMessageId(args: Map<String, Any>): Long {
        val raw = args["message_id"] ?: throw InvalidToolInputException("message_id is required")
        return when (raw) {
            is Number -> raw.toLong()
            is String -> raw.toLongOrNull()
                ?: throw InvalidToolInputException("message_id must be a valid number")
            else -> throw InvalidToolInputException("message_id must be a number")
        }
    }
}
