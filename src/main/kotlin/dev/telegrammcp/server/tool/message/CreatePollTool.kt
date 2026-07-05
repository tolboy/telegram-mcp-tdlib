package dev.telegrammcp.server.tool.message

import com.fasterxml.jackson.databind.ObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.exception.InvalidToolInputException
import dev.telegrammcp.server.model.AuditOutcome
import dev.telegrammcp.server.service.AuditService
import dev.telegrammcp.server.service.EntityResolverService
import dev.telegrammcp.server.service.GuardrailService
import dev.telegrammcp.server.service.OperationGuardService
import dev.telegrammcp.server.tool.McpToolHandler
import dev.telegrammcp.server.util.StructuredLogger
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import org.springframework.stereotype.Component

/**
 * MCP tool: **create_poll**
 *
 * Creates and sends a poll in a chat.
 */
@Component
class CreatePollTool(
    private val telegramClient: TelegramClientService,
    private val entityResolver: EntityResolverService,
    private val guardrailService: GuardrailService,
    private val operationGuardService: OperationGuardService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {

    private val log = StructuredLogger.forClass<CreatePollTool>()

    companion object {
        const val TOOL_NAME = "create_poll"
        private const val MAX_QUESTION = 255
        private const val MAX_OPTION = 100
        private const val MAX_OPTIONS = 10

        @Suppress("JsonStandardCompliance")
        private val INPUT_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "chat_id": { "type": ["string", "number"], "description": "Chat identifier (ID/@username/+phone)" },
            "question": { "type": "string", "description": "Poll question (1-255 chars)" },
            "options": {
              "type": "array",
              "items": { "type": "string" },
              "description": "Poll options (2-10 strings, each 1-100 chars)"
            },
            "is_anonymous": { "type": "boolean", "description": "Anonymous votes (default true)" },
            "allow_multiple_answers": { "type": "boolean", "description": "Allow multiple answers (default false)" }
          },
          "required": ["chat_id", "question", "options"]
        }
        """.trimIndent()
    }

    override fun definition(): McpSchema.Tool =
        dev.telegrammcp.server.tool.ToolSupport.definition(TOOL_NAME, "Create and send a poll in a Telegram chat", INPUT_SCHEMA, objectMapper)

    @Suppress("UNCHECKED_CAST")
    override fun execute(
        exchange: McpSyncServerExchange,
        arguments: Map<String, Any>,
    ): McpSchema.CallToolResult {
        val sample = Timer.start(meterRegistry)

        return try {
            operationGuardService.checkPermission(TOOL_NAME, arguments)

            val chatId = entityResolver.resolve(
                arguments["chat_id"] ?: throw InvalidToolInputException("chat_id is required"),
            )
            val question = arguments["question"]?.toString()?.takeIf { it.isNotBlank() }
                ?: throw InvalidToolInputException("question is required")
            if (question.length > MAX_QUESTION) {
                throw InvalidToolInputException("question must be $MAX_QUESTION characters or fewer")
            }

            val rawOptions = arguments["options"] as? List<*>
                ?: throw InvalidToolInputException("options is required and must be a list of strings")
            val options = rawOptions.mapNotNull { it?.toString()?.takeIf { s -> s.isNotBlank() } }
            if (options.size < 2) throw InvalidToolInputException("options must contain at least 2 entries")
            if (options.size > MAX_OPTIONS) throw InvalidToolInputException("options must contain at most $MAX_OPTIONS entries")
            options.forEach { opt ->
                if (opt.length > MAX_OPTION) {
                    throw InvalidToolInputException("each option must be $MAX_OPTION characters or fewer")
                }
            }

            val isAnonymous = arguments["is_anonymous"]?.toString()?.toBoolean() ?: true
            val allowMultiple = arguments["allow_multiple_answers"]?.toString()?.toBoolean() ?: false

            guardrailService.validateInput(question)
            options.forEach(guardrailService::validateInput)
            guardrailService.validateChatAccess(chatId)

            log.withTool(TOOL_NAME).info("Creating poll in chat {} ({} options)", chatId, options.size)

            val message = telegramClient.sendPoll(chatId, question, options, isAnonymous, allowMultiple)
            auditService.record(TOOL_NAME, arguments, AuditOutcome.SUCCESS)

            val json = objectMapper.writeValueAsString(message)
            dev.telegrammcp.server.tool.ToolSupport.textResult(json)
        } catch (ex: Exception) {
            log.withTool(TOOL_NAME).error("Failed to create poll: {}", ex.message, ex)
            auditService.record(TOOL_NAME, arguments, AuditOutcome.ERROR, error = ex.message)
            dev.telegrammcp.server.tool.ToolSupport.errorText("Error: ${ex.message}")
        } finally {
            sample.stop(Timer.builder("mcp.tool.execution").tag("tool", TOOL_NAME).register(meterRegistry))
        }
    }
}
