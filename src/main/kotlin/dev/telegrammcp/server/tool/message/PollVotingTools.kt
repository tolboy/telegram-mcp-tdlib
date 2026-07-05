package dev.telegrammcp.server.tool.message

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
 * MCP tool: **vote_poll** — votes in a poll by 0-based option indexes.
 */
@Component
class VotePollTool(
    private val telegramClient: TelegramClientService,
    private val entityResolver: EntityResolverService,
    private val guardrailService: GuardrailService,
    private val operationGuardService: OperationGuardService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {

    private val log = StructuredLogger.forClass<VotePollTool>()

    companion object {
        const val TOOL_NAME = "vote_poll"

        @Suppress("JsonStandardCompliance")
        private val INPUT_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "chat_id": { "type": ["string", "number"], "description": "Chat identifier (ID/@username/+phone)" },
            "message_id": { "type": "number", "description": "Message ID containing the poll" },
            "option_ids": {
              "type": "array",
              "items": { "type": "number" },
              "description": "0-based option indexes to vote for; empty retracts the vote where Telegram allows it"
            }
          },
          "required": ["chat_id", "message_id", "option_ids"]
        }
        """.trimIndent()
    }

    override fun definition(): McpSchema.Tool = ToolSupport.definition(
        TOOL_NAME,
        "Vote in a Telegram poll by selecting option indexes",
        INPUT_SCHEMA,
        objectMapper,
    )

    override fun execute(exchange: McpSyncServerExchange, arguments: Map<String, Any>): McpSchema.CallToolResult =
        ToolSupport.execute(TOOL_NAME, arguments, objectMapper, meterRegistry, log, "Failed to vote in poll", auditService) {
            operationGuardService.checkPermission(TOOL_NAME, arguments)

            val chatId = entityResolver.resolve(
                arguments["chat_id"] ?: throw InvalidToolInputException("chat_id is required"),
            )
            val messageId = PollToolInputs.requiredMessageId(arguments)
            val rawOptions = arguments["option_ids"] as? List<*>
                ?: throw InvalidToolInputException("option_ids is required and must be a list of numbers")
            val optionIds = rawOptions.map {
                (it as? Number)?.toInt()
                    ?: it?.toString()?.toIntOrNull()
                    ?: throw InvalidToolInputException("option_ids entries must be numbers")
            }
            if (optionIds.any { it < 0 }) throw InvalidToolInputException("option_ids must be non-negative")

            guardrailService.validateChatAccess(chatId)
            log.withTool(TOOL_NAME).info("Voting in poll message {} in chat {} ({} options)", messageId, chatId, optionIds.size)
            telegramClient.setPollAnswer(chatId, messageId, optionIds)
            mapOf("chat_id" to chatId, "message_id" to messageId, "voted_option_ids" to optionIds)
        }
}

/**
 * MCP tool: **close_poll** — permanently closes a poll (irreversible).
 */
@Component
class ClosePollTool(
    private val telegramClient: TelegramClientService,
    private val entityResolver: EntityResolverService,
    private val guardrailService: GuardrailService,
    private val operationGuardService: OperationGuardService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {

    private val log = StructuredLogger.forClass<ClosePollTool>()

    companion object {
        const val TOOL_NAME = "close_poll"

        @Suppress("JsonStandardCompliance")
        private val INPUT_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "chat_id": { "type": ["string", "number"], "description": "Chat identifier (ID/@username/+phone)" },
            "message_id": { "type": "number", "description": "Message ID containing the poll" },
            "confirmed": { "type": "boolean", "description": "Required when confirmation mode is enabled" }
          },
          "required": ["chat_id", "message_id"]
        }
        """.trimIndent()
    }

    override fun definition(): McpSchema.Tool = ToolSupport.definition(
        TOOL_NAME,
        "Permanently close a Telegram poll so no further votes are accepted",
        INPUT_SCHEMA,
        objectMapper,
    )

    override fun execute(exchange: McpSyncServerExchange, arguments: Map<String, Any>): McpSchema.CallToolResult =
        ToolSupport.execute(TOOL_NAME, arguments, objectMapper, meterRegistry, log, "Failed to close poll", auditService) {
            operationGuardService.checkPermission(TOOL_NAME, arguments)

            val chatId = entityResolver.resolve(
                arguments["chat_id"] ?: throw InvalidToolInputException("chat_id is required"),
            )
            val messageId = PollToolInputs.requiredMessageId(arguments)

            guardrailService.validateChatAccess(chatId)
            log.withTool(TOOL_NAME).info("Closing poll message {} in chat {}", messageId, chatId)
            telegramClient.stopPoll(chatId, messageId)
            mapOf("chat_id" to chatId, "message_id" to messageId, "closed" to true)
        }
}

private object PollToolInputs {
    fun requiredMessageId(arguments: Map<String, Any>): Long =
        (arguments["message_id"] as? Number)?.toLong()
            ?: arguments["message_id"]?.toString()?.toLongOrNull()
            ?: throw InvalidToolInputException("message_id is required and must be a number")
}
