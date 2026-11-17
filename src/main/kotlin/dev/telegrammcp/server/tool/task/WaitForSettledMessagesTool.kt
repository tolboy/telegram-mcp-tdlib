package dev.telegrammcp.server.tool.task

import com.fasterxml.jackson.databind.ObjectMapper
import dev.telegrammcp.server.client.TelegramAccountContext
import dev.telegrammcp.server.service.AuditService
import dev.telegrammcp.server.service.EntityResolverService
import dev.telegrammcp.server.service.GuardrailService
import dev.telegrammcp.server.service.OperationGuardService
import dev.telegrammcp.server.service.SettleRequest
import dev.telegrammcp.server.service.SettledMessageWaiter
import dev.telegrammcp.server.service.SettledWaitResult
import dev.telegrammcp.server.tool.McpToolHandler
import dev.telegrammcp.server.tool.ToolSupport
import dev.telegrammcp.server.util.StructuredLogger
import io.micrometer.core.instrument.MeterRegistry
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import org.springframework.stereotype.Component
import java.time.Duration

/** Debounced wait for incoming bursts on the change-journal cursor; reads only. */
@Component
class WaitForSettledMessagesTool(
    private val waiter: SettledMessageWaiter,
    private val accounts: TelegramAccountContext,
    private val resolver: EntityResolverService,
    private val guard: GuardrailService,
    private val operationGuard: OperationGuardService,
    private val audit: AuditService,
    private val mapper: ObjectMapper,
    private val metrics: MeterRegistry,
) : McpToolHandler {
    companion object {
        const val TOOL_NAME = "wait_for_settled_messages"
        private val INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "cursor": {"type": "string", "description": "Resume a nextCursor from this tool, changes_since or wait_for_changes. Omit to start at the current journal tail."},
                "chat_id": {"type": ["string", "integer"], "description": "Settle one conversation; access is validated before waiting. Without it, the wait settles when every visible chat has been quiet."},
                "settle_seconds": {"type": "integer", "minimum": 0, "maximum": 20, "default": 5, "description": "Quiet time after the last incoming message before the burst counts as finished."},
                "timeout_seconds": {"type": "integer", "minimum": 0, "maximum": 30, "default": 25},
                "limit": {"type": "integer", "minimum": 1, "maximum": 200, "default": 50, "description": "Most messages per result; reaching it returns status=limit before the chats go quiet."},
                "addressed_only": {"type": "boolean", "default": false, "description": "Count only messages addressed to the account: private chats, mentions, and replies to its messages."}
              }
            }
        """.trimIndent()
    }

    override fun definition() = ToolSupport.definition(TOOL_NAME,
        "Wait up to 30 seconds until new incoming messages have stopped arriving for settle_seconds, then return them " +
            "(IDs and addressed flags, no content). Resume nextCursor after every status, including timed_out. " +
            "gap or reset means coverage broke: catch up from history before continuing. No read receipts change.",
        INPUT_SCHEMA, mapper)

    override fun execute(exchange: McpSyncServerExchange, arguments: Map<String, Any>): McpSchema.CallToolResult =
        ToolSupport.execute(TOOL_NAME, arguments, mapper, metrics, StructuredLogger.forClass<WaitForSettledMessagesTool>(),
            "Settled wait failed", audit) { run(arguments) }

    fun run(args: Map<String, Any>): SettledWaitResult {
        operationGuard.checkPermission(TOOL_NAME, args)
        val limit = args.integer("limit", 50, 1, 200)
        val timeout = args.integer("timeout_seconds", 25, 0, 30)
        val settle = args.integer("settle_seconds", 5, 0, SettleRequest.MAX_SETTLE_SECONDS.toInt())
        val addressedOnly = args["addressed_only"]?.let {
            require(it is Boolean) { "addressed_only must be a boolean" }
            it
        } ?: false
        val cursor = args["cursor"]?.let {
            require(it is String && it.isNotBlank()) { "cursor must be a nonempty string" }
            it
        }
        val chatId = args["chat_id"]?.let(resolver::resolve)
        chatId?.let(guard::validateChatAccess)
        val request = SettleRequest(cursor, chatId, Duration.ofSeconds(settle.toLong()), Duration.ofSeconds(timeout.toLong()),
            limit, addressedOnly)
        return waiter.await(accounts.currentAccount(), request) { guard.isChatAllowed(it) }
    }
}
