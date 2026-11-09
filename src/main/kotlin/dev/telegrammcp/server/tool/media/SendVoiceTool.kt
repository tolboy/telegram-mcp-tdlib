package dev.telegrammcp.server.tool.media

import com.fasterxml.jackson.databind.ObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.exception.InvalidToolInputException
import dev.telegrammcp.server.model.AuditOutcome
import dev.telegrammcp.server.service.AuditService
import dev.telegrammcp.server.service.EntityResolverService
import dev.telegrammcp.server.service.FileSecurityService
import dev.telegrammcp.server.service.GuardrailService
import dev.telegrammcp.server.service.OperationGuardService
import dev.telegrammcp.server.tool.McpToolHandler
import dev.telegrammcp.server.tool.ToolNumbers
import dev.telegrammcp.server.util.StructuredLogger
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import org.springframework.stereotype.Component

/**
 * MCP tool: **send_voice**
 *
 * Sends a voice note to a chat. File path is validated by [FileSecurityService].
 */
@Component
class SendVoiceTool(
    private val telegramClient: TelegramClientService,
    private val entityResolver: EntityResolverService,
    private val guardrailService: GuardrailService,
    private val fileSecurityService: FileSecurityService,
    private val operationGuardService: OperationGuardService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
    private val sendOperations: dev.telegrammcp.server.service.SendOperationService? = null,
) : McpToolHandler {

    private val log = StructuredLogger.forClass<SendVoiceTool>()

    companion object {
        const val TOOL_NAME = "send_voice"

        @Suppress("JsonStandardCompliance")
        private val INPUT_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "chat_id": { "type": ["string", "integer"], "description": "Chat identifier (ID/@username/+phone)" },
            "file_path": { "type": "string", "description": "Local OGG/Opus voice file path" },
            "duration": { "type": "integer", "description": "Voice duration in seconds (optional)" },
            "caption": { "type": "string", "description": "Optional caption" }
          },
          "required": ["chat_id", "file_path"]
        }
        """.trimIndent()
    }

    override fun definition(): McpSchema.Tool =
        dev.telegrammcp.server.tool.ToolSupport.definition(TOOL_NAME, "Send a voice note (OGG/Opus) to a Telegram chat", INPUT_SCHEMA, objectMapper)

    override fun execute(
        exchange: McpSyncServerExchange,
        arguments: Map<String, Any>,
    ): McpSchema.CallToolResult {
        val sample = Timer.start(meterRegistry)

        return try {
            dev.telegrammcp.server.tool.RecoverableSend.check(operationGuardService, TOOL_NAME, arguments)

            val chatId = entityResolver.resolve(
                arguments["chat_id"] ?: throw InvalidToolInputException("chat_id is required"),
            )
            val rawPath = arguments["file_path"]?.toString()
                ?: throw InvalidToolInputException("file_path is required")
            val duration = ToolNumbers.specifiedInt(arguments, "duration") ?: 0
            val caption = arguments["caption"]?.toString()

            guardrailService.validateChatAccess(chatId)
            if (caption != null) guardrailService.validateInput(caption)

            log.withTool(TOOL_NAME).info("Sending voice '{}' to chat {}", rawPath, chatId)
            val sourcePath = fileSecurityService.validateForUpload(rawPath)
            val message = fileSecurityService.withStagedUpload(rawPath) { staged ->
                dev.telegrammcp.server.tool.RecoverableSend.execute(sendOperations, operationGuardService, TOOL_NAME,
                    arguments, chatId, listOf(sourcePath.toString(), duration, caption,
                        dev.telegrammcp.server.tool.RecoverableSend.fileFingerprint(arguments, staged))) {
                    telegramClient.sendVoice(chatId, staged.toString(), duration, caption)
                }
            }
            auditService.record(TOOL_NAME, arguments, AuditOutcome.SUCCESS)

            val json = objectMapper.writeValueAsString(message)
            dev.telegrammcp.server.tool.ToolSupport.textResult(json)
        } catch (ex: Exception) {
            log.withTool(TOOL_NAME).error("Failed to send voice: {}", ex.message, ex)
            auditService.record(TOOL_NAME, arguments, AuditService.outcomeFor(ex), error = ex.message)
            dev.telegrammcp.server.tool.ToolSupport.errorResult(ex)
        } finally {
            sample.stop(Timer.builder("mcp.tool.execution").tag("tool", TOOL_NAME).register(meterRegistry))
        }
    }
}
