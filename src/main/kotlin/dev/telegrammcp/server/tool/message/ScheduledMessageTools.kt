package dev.telegrammcp.server.tool.message

import com.fasterxml.jackson.databind.ObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.exception.InvalidToolInputException
import dev.telegrammcp.server.service.AuditService
import dev.telegrammcp.server.service.EntityResolverService
import dev.telegrammcp.server.service.GuardrailService
import dev.telegrammcp.server.service.OperationGuardService
import dev.telegrammcp.server.tool.McpToolHandler
import dev.telegrammcp.server.tool.ToolInputParsers
import dev.telegrammcp.server.tool.ToolSupport
import dev.telegrammcp.server.util.StructuredLogger
import io.micrometer.core.instrument.MeterRegistry
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class ListScheduledMessagesTool(
    private val telegramClient: TelegramClientService,
    private val entityResolver: EntityResolverService,
    private val guardrailService: GuardrailService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {
    companion object {
        const val TOOL_NAME = "list_scheduled_messages"
        private const val INPUT_SCHEMA = """
        {"type":"object","properties":{"chat_id":{"type":["string","number"],"description":"Chat identifier"}},"required":["chat_id"]}
        """
    }
    private val log = StructuredLogger.forClass<ListScheduledMessagesTool>()

    override fun definition(): McpSchema.Tool = ToolSupport.definition(TOOL_NAME, "List messages scheduled for a Telegram chat", INPUT_SCHEMA, objectMapper)

    override fun execute(exchange: McpSyncServerExchange, arguments: Map<String, Any>): McpSchema.CallToolResult =
        ToolSupport.execute(TOOL_NAME, arguments, objectMapper, meterRegistry, log, "Failed to list scheduled messages", auditService) {
            val chatId = ScheduledMessageInputs.resolveChat(arguments, entityResolver, guardrailService)
            telegramClient.getScheduledMessages(chatId)
        }
}

@Component
class ScheduleMessageTool(
    private val telegramClient: TelegramClientService,
    private val entityResolver: EntityResolverService,
    private val guardrailService: GuardrailService,
    private val operationGuardService: OperationGuardService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {
    companion object {
        const val TOOL_NAME = "schedule_message"
        private const val INPUT_SCHEMA = """
        {
          "type":"object",
          "properties":{
            "chat_id":{"type":["string","number"],"description":"Chat identifier"},
            "text":{"type":"string","description":"Message text"},
            "send_at":{"type":["string","number"],"description":"Future ISO-8601 instant or Unix epoch seconds"},
            "repeat_period_seconds":{"type":"number","description":"Optional repeat period in seconds; 0 disables repetition"},
            "disable_notification":{"type":"boolean","description":"Send without notification"},
            "parse_mode":{"type":"string","enum":["plain","html","markdown"]}
          },
          "required":["chat_id","text","send_at"]
        }
        """
    }
    private val log = StructuredLogger.forClass<ScheduleMessageTool>()

    override fun definition(): McpSchema.Tool = ToolSupport.definition(TOOL_NAME, "Schedule a Telegram text message for a future time", INPUT_SCHEMA, objectMapper)

    override fun execute(exchange: McpSyncServerExchange, arguments: Map<String, Any>): McpSchema.CallToolResult =
        ToolSupport.execute(TOOL_NAME, arguments, objectMapper, meterRegistry, log, "Failed to schedule message", auditService) {
            operationGuardService.checkPermission(TOOL_NAME, arguments)
            val chatId = ScheduledMessageInputs.resolveChat(arguments, entityResolver, guardrailService)
            val text = arguments["text"]?.toString()?.takeIf { it.isNotBlank() }
                ?: throw InvalidToolInputException("text is required")
            guardrailService.validateInput(text)
            telegramClient.scheduleMessage(
                chatId = chatId,
                text = text,
                sendAtEpochSeconds = ScheduledMessageInputs.futureEpochSeconds(arguments, "send_at"),
                repeatPeriodSeconds = ScheduledMessageInputs.repeatPeriod(arguments),
                disableNotification = ScheduledMessageInputs.optionalBoolean(arguments, "disable_notification"),
                parseMode = ToolInputParsers.parseMode(arguments),
            )
        }
}

@Component
class RescheduleMessageTool(
    private val telegramClient: TelegramClientService,
    private val entityResolver: EntityResolverService,
    private val guardrailService: GuardrailService,
    private val operationGuardService: OperationGuardService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {
    companion object {
        const val TOOL_NAME = "reschedule_message"
        private const val INPUT_SCHEMA = """
        {"type":"object","properties":{"chat_id":{"type":["string","number"]},"message_id":{"type":["string","number"]},"send_at":{"type":["string","number"]},"repeat_period_seconds":{"type":"number"}},"required":["chat_id","message_id","send_at"]}
        """
    }
    private val log = StructuredLogger.forClass<RescheduleMessageTool>()

    override fun definition(): McpSchema.Tool = ToolSupport.definition(TOOL_NAME, "Change the send time of a scheduled Telegram message", INPUT_SCHEMA, objectMapper)

    override fun execute(exchange: McpSyncServerExchange, arguments: Map<String, Any>): McpSchema.CallToolResult =
        ToolSupport.execute(TOOL_NAME, arguments, objectMapper, meterRegistry, log, "Failed to reschedule message", auditService) {
            operationGuardService.checkPermission(TOOL_NAME, arguments)
            val chatId = ScheduledMessageInputs.resolveChat(arguments, entityResolver, guardrailService)
            val messageId = ScheduledMessageInputs.requiredPositiveLong(arguments, "message_id")
            val scheduled = telegramClient.rescheduleMessage(
                chatId,
                messageId,
                ScheduledMessageInputs.futureEpochSeconds(arguments, "send_at"),
                ScheduledMessageInputs.repeatPeriod(arguments),
            )
            mapOf(
                "chat_id" to chatId,
                "message_id" to scheduled.message.messageId,
                "previous_message_id" to messageId,
                "send_at" to scheduled.sendAt,
                "repeat_period_seconds" to scheduled.repeatPeriodSeconds,
                "rescheduled" to true,
            )
        }
}

@Component
class CancelScheduledMessageTool(
    private val telegramClient: TelegramClientService,
    private val entityResolver: EntityResolverService,
    private val guardrailService: GuardrailService,
    private val operationGuardService: OperationGuardService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {
    companion object {
        const val TOOL_NAME = "cancel_scheduled_message"
        private const val INPUT_SCHEMA = """
        {"type":"object","properties":{"chat_id":{"type":["string","number"]},"message_id":{"type":["string","number"]},"confirmed":{"type":"boolean","description":"Required when confirmation mode is enabled"}},"required":["chat_id","message_id"]}
        """
    }
    private val log = StructuredLogger.forClass<CancelScheduledMessageTool>()

    override fun definition(): McpSchema.Tool = ToolSupport.definition(TOOL_NAME, "Cancel a scheduled Telegram message before it is sent", INPUT_SCHEMA, objectMapper)

    override fun execute(exchange: McpSyncServerExchange, arguments: Map<String, Any>): McpSchema.CallToolResult =
        ToolSupport.execute(TOOL_NAME, arguments, objectMapper, meterRegistry, log, "Failed to cancel scheduled message", auditService) {
            operationGuardService.checkPermission(TOOL_NAME, arguments)
            val chatId = ScheduledMessageInputs.resolveChat(arguments, entityResolver, guardrailService)
            val messageId = ScheduledMessageInputs.requiredPositiveLong(arguments, "message_id")
            telegramClient.cancelScheduledMessage(chatId, messageId)
            mapOf("chat_id" to chatId, "message_id" to messageId, "cancelled" to true)
        }
}

private object ScheduledMessageInputs {
    fun resolveChat(arguments: Map<String, Any>, resolver: EntityResolverService, guardrails: GuardrailService): Long {
        val chatId = resolver.resolve(arguments["chat_id"] ?: throw InvalidToolInputException("chat_id is required"))
        guardrails.validateChatAccess(chatId)
        return chatId
    }

    fun futureEpochSeconds(arguments: Map<String, Any>, name: String): Int {
        val raw = arguments[name] ?: throw InvalidToolInputException("$name is required")
        val seconds = when (raw) {
            is Number -> raw.toLong()
            else -> raw.toString().trim().toLongOrNull() ?: runCatching { Instant.parse(raw.toString().trim()).epochSecond }
                .getOrElse { throw InvalidToolInputException("$name must be an ISO-8601 instant or Unix epoch seconds") }
        }
        if (seconds !in 1..Int.MAX_VALUE.toLong()) throw InvalidToolInputException("$name must be within TDLib's supported epoch range")
        if (seconds <= Instant.now().epochSecond) throw InvalidToolInputException("$name must be in the future")
        return seconds.toInt()
    }

    fun repeatPeriod(arguments: Map<String, Any>): Int = optionalNonNegativeInt(arguments, "repeat_period_seconds") ?: 0

    fun requiredPositiveLong(arguments: Map<String, Any>, name: String): Long = when (val raw = arguments[name]) {
        is Number -> raw.toLong()
        null -> throw InvalidToolInputException("$name is required")
        else -> raw.toString().trim().toLongOrNull() ?: throw InvalidToolInputException("$name must be an integer")
    }.also { if (it <= 0) throw InvalidToolInputException("$name must be a positive integer") }

    fun optionalBoolean(arguments: Map<String, Any>, name: String): Boolean = when (val raw = arguments[name]) {
        null -> false
        is Boolean -> raw
        is String -> when (raw.lowercase()) {
            "true" -> true
            "false" -> false
            else -> throw InvalidToolInputException("$name must be a boolean")
        }
        else -> throw InvalidToolInputException("$name must be a boolean")
    }

    private fun optionalNonNegativeInt(arguments: Map<String, Any>, name: String): Int? = when (val raw = arguments[name]) {
        null -> null
        is Number -> raw.toInt().takeIf { raw.toDouble() == it.toDouble() }
        else -> raw.toString().trim().toIntOrNull()
    }?.also { if (it < 0) throw InvalidToolInputException("$name must be zero or positive") }
        ?: if (arguments.containsKey(name)) throw InvalidToolInputException("$name must be an integer") else null
}
