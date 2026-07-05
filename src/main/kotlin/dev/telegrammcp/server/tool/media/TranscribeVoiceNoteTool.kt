package dev.telegrammcp.server.tool.media

import com.fasterxml.jackson.databind.ObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.exception.InvalidToolInputException
import dev.telegrammcp.server.model.AuditOutcome
import dev.telegrammcp.server.service.AuditService
import dev.telegrammcp.server.service.EntityResolverService
import dev.telegrammcp.server.service.GuardrailService
import dev.telegrammcp.server.service.OperationGuardService
import dev.telegrammcp.server.tool.McpToolHandler
import dev.telegrammcp.server.tool.ToolSupport
import dev.telegrammcp.server.util.StructuredLogger
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import org.springframework.stereotype.Component

/**
 * MCP tool: **transcribe_voice_note**
 *
 * Requests Telegram's built-in transcript for a voice message. Telegram keeps
 * that transcript on the original message; this tool only returns the result
 * and never posts a separate message. It can consume a Premium or trial
 * recognition allowance, so it is blocked by read-only mode and rate-limited.
 */
@Component
class TranscribeVoiceNoteTool(
    private val telegramClient: TelegramClientService,
    private val entityResolver: EntityResolverService,
    private val guardrailService: GuardrailService,
    private val operationGuardService: OperationGuardService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {

    private val log = StructuredLogger.forClass<TranscribeVoiceNoteTool>()

    companion object {
        const val TOOL_NAME = "transcribe_voice_note"

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
              "description": "ID of the voice-note message to transcribe"
            }
          },
          "required": ["chat_id", "message_id"]
        }
        """.trimIndent()
    }

    override fun definition(): McpSchema.Tool = ToolSupport.definition(
        TOOL_NAME,
        "Request Telegram's native transcript for a voice note; requires Premium or an available Telegram trial allowance and does not send a new message",
        INPUT_SCHEMA,
        objectMapper,
    )

    override fun execute(
        exchange: McpSyncServerExchange,
        arguments: Map<String, Any>,
    ): McpSchema.CallToolResult {
        val sample = Timer.start(meterRegistry)
        val startMs = System.currentTimeMillis()

        return try {
            operationGuardService.checkPermission(TOOL_NAME, arguments)
            val chatId = resolveChatId(arguments)
            val messageId = extractMessageId(arguments)
            guardrailService.validateChatAccess(chatId)

            log.withTool(TOOL_NAME).info("Transcribing voice note {} in chat {}", messageId, chatId)
            val transcription = telegramClient.transcribeVoiceNote(chatId, messageId)
            auditService.record(
                TOOL_NAME,
                arguments,
                AuditOutcome.SUCCESS,
                durationMs = System.currentTimeMillis() - startMs,
            )
            ToolSupport.textResult(objectMapper.writeValueAsString(transcription))
        } catch (ex: Exception) {
            log.withTool(TOOL_NAME).error("Failed to transcribe voice note: {}", ex.message, ex)
            auditService.record(TOOL_NAME, arguments, AuditOutcome.ERROR, error = ex.message)
            ToolSupport.errorText("Error: ${ex.message}")
        } finally {
            sample.stop(Timer.builder("mcp.tool.execution").tag("tool", TOOL_NAME).register(meterRegistry))
        }
    }

    private fun resolveChatId(args: Map<String, Any>): Long = entityResolver.resolve(
        args["chat_id"] ?: throw InvalidToolInputException("chat_id is required"),
    )

    private fun extractMessageId(args: Map<String, Any>): Long = when (val raw = args["message_id"]) {
        is Number -> raw.toLong()
        is String -> raw.toLongOrNull()
            ?: throw InvalidToolInputException("message_id must be a valid number")
        null -> throw InvalidToolInputException("message_id is required")
        else -> throw InvalidToolInputException("message_id must be a number")
    }
}
