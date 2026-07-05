package dev.telegrammcp.server.tool.chat

import com.fasterxml.jackson.databind.ObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.exception.InvalidToolInputException
import dev.telegrammcp.server.model.AuditOutcome
import dev.telegrammcp.server.service.AuditService
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
 * MCP tool: **subscribe_public_channel**
 *
 * Subscribes to a public channel or supergroup by username. Safe to call
 * repeatedly — if the account is already a member, the tool reports that
 * instead of failing.
 */
@Component
class SubscribePublicChannelTool(
    private val telegramClient: TelegramClientService,
    private val guardrailService: GuardrailService,
    private val operationGuardService: OperationGuardService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {

    private val log = StructuredLogger.forClass<SubscribePublicChannelTool>()

    companion object {
        const val TOOL_NAME = "subscribe_public_channel"

        @Suppress("JsonStandardCompliance")
        private val INPUT_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "channel": {
              "type": "string",
              "description": "Public channel or supergroup username (e.g. @channel_name or channel_name)"
            }
          },
          "required": ["channel"]
        }
        """.trimIndent()
    }

    override fun definition(): McpSchema.Tool =
        dev.telegrammcp.server.tool.ToolSupport.definition(TOOL_NAME, "Subscribe to a public Telegram channel or supergroup by username", INPUT_SCHEMA, objectMapper)

    override fun execute(
        exchange: McpSyncServerExchange,
        arguments: Map<String, Any>,
    ): McpSchema.CallToolResult {
        val sample = Timer.start(meterRegistry)

        return try {
            operationGuardService.checkPermission(TOOL_NAME, arguments)

            val channel = arguments["channel"]?.toString()?.takeIf { it.isNotBlank() }
                ?: throw InvalidToolInputException("channel is required")

            guardrailService.validateInput(channel)
            log.withTool(TOOL_NAME).info("Subscribing to public channel @{}", channel.removePrefix("@"))

            val chatInfo = telegramClient.joinPublicChat(channel)
            auditService.record(TOOL_NAME, arguments, AuditOutcome.SUCCESS)

            val json = objectMapper.writeValueAsString(chatInfo)
            dev.telegrammcp.server.tool.ToolSupport.textResult(json)
        } catch (ex: Exception) {
            val msg = ex.message ?: "Unknown error"
            // Treat "already a participant" as a soft success
            if (msg.contains("already", ignoreCase = true) && msg.contains("participant", ignoreCase = true)) {
                log.withTool(TOOL_NAME).info("Already a member of @{}", channel(arguments))
                auditService.record(TOOL_NAME, arguments, AuditOutcome.SUCCESS)
                val json = objectMapper.writeValueAsString(
                    mapOf("channel" to channel(arguments), "already_member" to true),
                )
                dev.telegrammcp.server.tool.ToolSupport.textResult(json)
            } else {
                log.withTool(TOOL_NAME).error("Failed to subscribe to channel: {}", msg, ex)
                auditService.record(TOOL_NAME, arguments, AuditOutcome.ERROR, error = msg)
                dev.telegrammcp.server.tool.ToolSupport.errorText("Error: $msg")
            }
        } finally {
            sample.stop(Timer.builder("mcp.tool.execution").tag("tool", TOOL_NAME).register(meterRegistry))
        }
    }

    private fun channel(arguments: Map<String, Any>): String =
        arguments["channel"]?.toString()?.removePrefix("@") ?: ""
}
