package dev.telegrammcp.server.tool.message

import com.fasterxml.jackson.databind.ObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.exception.InvalidToolInputException
import dev.telegrammcp.server.service.AuditService
import dev.telegrammcp.server.service.GuardrailService
import dev.telegrammcp.server.tool.McpToolHandler
import dev.telegrammcp.server.tool.ToolSupport
import dev.telegrammcp.server.util.StructuredLogger
import io.micrometer.core.instrument.MeterRegistry
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import org.springframework.stereotype.Component

/**
 * MCP tool: **message_from_link** — resolves a t.me message link to the actual message.
 */
@Component
class MessageFromLinkTool(
    private val telegramClient: TelegramClientService,
    private val guardrailService: GuardrailService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {

    private val log = StructuredLogger.forClass<MessageFromLinkTool>()

    companion object {
        const val TOOL_NAME = "message_from_link"

        private val LINK_PATTERN = Regex("""^https?://t\.me/""")

        @Suppress("JsonStandardCompliance")
        private val INPUT_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "link": { "type": "string", "description": "Telegram message link (e.g. https://t.me/c/123456/789 or https://t.me/channel/123)" }
          },
          "required": ["link"]
        }
        """.trimIndent()
    }

    override fun definition(): McpSchema.Tool = ToolSupport.definition(
        name = TOOL_NAME,
        description = "Resolve a t.me message link and return the referenced message",
        inputSchema = INPUT_SCHEMA,
        objectMapper = objectMapper,
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
        failureMessage = "Failed to resolve message link",
        auditService = auditService,
    ) {
            val link = arguments["link"]?.toString()?.takeIf { it.isNotBlank() }
                ?: throw InvalidToolInputException("link is required")

            guardrailService.validateInput(link)

            if (!LINK_PATTERN.containsMatchIn(link)) {
                throw InvalidToolInputException("link must be a valid t.me URL")
            }

            log.withTool(TOOL_NAME).info("Resolving message from link")
            val message = telegramClient.getMessageByLink(link)
            guardrailService.validateChatAccess(message.chatId)
            message
        }
}
