package dev.telegrammcp.server.tool.task

import com.fasterxml.jackson.databind.ObjectMapper
import dev.telegrammcp.server.client.TelegramAccountContext
import dev.telegrammcp.server.service.AuditService
import dev.telegrammcp.server.service.ChangeJournal
import dev.telegrammcp.server.service.ChangeWaitResult
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
import java.time.Duration

/** Long polling of the same durable, account-scoped cursor used by changes_since. */
@Component
class WaitForChangesTool(
    private val journal: ChangeJournal,
    private val accounts: TelegramAccountContext,
    private val resolver: EntityResolverService,
    private val guard: GuardrailService,
    private val operationGuard: OperationGuardService,
    private val audit: AuditService,
    private val mapper: ObjectMapper,
    private val metrics: MeterRegistry,
) : McpToolHandler {
    companion object {
        const val TOOL_NAME = "wait_for_changes"
        private val INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "cursor": {"type": "string", "description": "Resume a changes_since or wait_for_changes cursor. Omit to start at the current journal tail."},
                "chat_id": {"type": ["string", "number"], "description": "Optional chat filter; access is validated before waiting."},
                "limit": {"type": "integer", "minimum": 1, "maximum": 200},
                "timeout_seconds": {"type": "integer", "minimum": 0, "maximum": 30, "default": 20}
              }
            }
        """.trimIndent()
    }

    override fun definition() = ToolSupport.definition(TOOL_NAME,
        "Wait up to 30 seconds for locally observed message changes; omit cursor to start now. " +
            "Resume nextCursor, including after timedOut=true. A gap requires history reconciliation; no read receipts change.",
        INPUT_SCHEMA, mapper)

    override fun execute(exchange: McpSyncServerExchange, arguments: Map<String, Any>): McpSchema.CallToolResult =
        ToolSupport.execute(TOOL_NAME, arguments, mapper, metrics, StructuredLogger.forClass<WaitForChangesTool>(),
            "Change wait failed", audit) { run(arguments) }

    fun run(args: Map<String, Any>): ChangeWaitResult {
        operationGuard.checkPermission(TOOL_NAME, args)
        val limit = args.integer("limit", 100, 1, 200)
        val timeout = args.integer("timeout_seconds", 20, 0, 30)
        val cursor = args["cursor"]?.let {
            require(it is String && it.isNotBlank()) { "cursor must be a nonempty string" }
            it
        }
        val chatId = args["chat_id"]?.let(resolver::resolve)
        chatId?.let(guard::validateChatAccess)
        return journal.waitForChanges(accounts.currentAccount(), cursor, limit, Duration.ofSeconds(timeout.toLong())) {
            (chatId == null || it == chatId) && guard.isChatAllowed(it)
        }
    }
}
