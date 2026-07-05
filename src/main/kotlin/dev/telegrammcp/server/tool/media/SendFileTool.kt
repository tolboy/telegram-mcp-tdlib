package dev.telegrammcp.server.tool.media

import com.fasterxml.jackson.databind.ObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.exception.InvalidToolInputException
import dev.telegrammcp.server.service.AuditService
import dev.telegrammcp.server.service.EntityResolverService
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
 * MCP tool: **send_file**
 *
 * Sends a local file to a Telegram chat. The file path is validated against
 * the file security policy (allowed roots, path traversal protection, size limits).
 *
 * This is a **write** tool and is blocked in read-only mode.
 */
@Component
class SendFileTool(
    private val telegramClient: TelegramClientService,
    private val entityResolver: EntityResolverService,
    private val guardrailService: GuardrailService,
    private val fileSecurityService: FileSecurityService,
    private val operationGuardService: OperationGuardService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {

    private val log = StructuredLogger.forClass<SendFileTool>()

    companion object {
        const val TOOL_NAME = "send_file"

        @Suppress("JsonStandardCompliance")
        private val INPUT_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "chat_id": {
              "type": ["string", "number"],
              "description": "Chat identifier: numeric ID, @username, +phone, or the canonical value self"
            },
            "file_path": {
              "type": "string",
              "description": "Local file path to upload (must be within allowed roots)"
            },
            "caption": {
              "type": "string",
              "description": "Optional caption for the file"
            }
          },
          "required": ["chat_id", "file_path"]
        }
        """.trimIndent()
    }

    override fun definition(): McpSchema.Tool = ToolSupport.definition(
        TOOL_NAME,
        "Send a local file to a Telegram chat (validated against security policy)",
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
        failureMessage = "Failed to send file",
        auditService = auditService,
    ) {
            // Check write permissions first
            operationGuardService.checkPermission(TOOL_NAME, arguments)

            val chatId = resolveChatId(arguments)
            val rawFilePath = arguments["file_path"]?.toString()
                ?: throw InvalidToolInputException("file_path is required")
            val caption = arguments["caption"]?.toString()

            log.withTool(TOOL_NAME).info("Sending file '{}' to chat {}", rawFilePath, chatId)

            // Validate file path security
            val validatedPath = fileSecurityService.validateForUpload(rawFilePath)

            guardrailService.validateChatAccess(chatId)
            if (caption != null) {
                guardrailService.validateInput(caption)
            }

            telegramClient.sendFile(chatId, validatedPath.toString(), caption)
    }

    private fun resolveChatId(args: Map<String, Any>): Long {
        val raw = args["chat_id"] ?: throw InvalidToolInputException("chat_id is required")
        return entityResolver.resolve(raw)
    }
}
