package dev.telegrammcp.server.tool.message

import com.fasterxml.jackson.databind.ObjectMapper
import dev.telegrammcp.server.exception.InvalidToolInputException
import dev.telegrammcp.server.service.AuditService
import dev.telegrammcp.server.service.EntityResolverService
import dev.telegrammcp.server.service.GuardrailService
import dev.telegrammcp.server.service.SendOperationService
import dev.telegrammcp.server.service.SendOperationJournal
import dev.telegrammcp.server.tool.McpToolHandler
import dev.telegrammcp.server.tool.ToolSupport
import dev.telegrammcp.server.util.StructuredLogger
import io.micrometer.core.instrument.MeterRegistry
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import org.springframework.stereotype.Component

@Component
class GetSendOperationTool(
    private val operations: SendOperationService,
    private val resolver: EntityResolverService,
    private val guard: GuardrailService,
    private val audit: AuditService,
    private val mapper: ObjectMapper,
    private val metrics: MeterRegistry,
) : McpToolHandler {
    override fun definition(): McpSchema.Tool = ToolSupport.definition(
        "get_send_operation",
        "Read the selected account's saved send receipt. SENT confirms a stored final message ID; UNKNOWN requires checking delivery, never automatic resend; NOT_FOUND means no saved reservation. An active send returns OPERATION_IN_PROGRESS.",
        """{"type":"object","properties":{
          "chat_id":{"type":["string","number"],"description":"Destination chat identifier, as used for the send."},
          "idempotency_key":{"type":"string","description":"Key supplied to send_message or reply_to_message."}
        },"required":["chat_id","idempotency_key"]}""", mapper,
    )

    override fun execute(exchange: McpSyncServerExchange, arguments: Map<String, Any>): McpSchema.CallToolResult =
        ToolSupport.execute("get_send_operation", arguments, mapper, metrics,
            StructuredLogger.forClass<GetSendOperationTool>(), "Unable to read send operation", audit) {
            val key = arguments["idempotency_key"] as? String
                ?: throw InvalidToolInputException("idempotency_key must be a string")
            SendOperationJournal.validateKey(key)
            val chatId = resolver.resolve(arguments["chat_id"] ?: throw InvalidToolInputException("chat_id is required"))
            guard.validateChatAccess(chatId)
            operations.status(key, chatId)
        }
}
