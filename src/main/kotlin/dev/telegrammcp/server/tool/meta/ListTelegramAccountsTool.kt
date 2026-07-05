package dev.telegrammcp.server.tool.meta

import com.fasterxml.jackson.databind.ObjectMapper
import dev.telegrammcp.server.client.TelegramAccountRegistry
import dev.telegrammcp.server.security.AccountAccessPolicy
import dev.telegrammcp.server.tool.AccountAgnosticMcpToolHandler
import dev.telegrammcp.server.tool.ToolSupport
import dev.telegrammcp.server.util.StructuredLogger
import io.micrometer.core.instrument.MeterRegistry
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import org.springframework.stereotype.Component

/** Lists account labels visible to the authenticated MCP client without exposing PII. */
@Component
class ListTelegramAccountsTool(
    private val registry: TelegramAccountRegistry,
    private val accountAccessPolicy: AccountAccessPolicy,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : AccountAgnosticMcpToolHandler {

    private val log = StructuredLogger.forClass<ListTelegramAccountsTool>()

    override fun definition(): McpSchema.Tool = ToolSupport.definition(
        name = "list_accounts",
        description = "List configured Telegram account labels available to this MCP client. Labels are safe routing identifiers, not profile names or phone numbers.",
        inputSchema = """{"type":"object","properties":{},"required":[]}""",
        objectMapper = objectMapper,
    )

    override fun execute(exchange: McpSyncServerExchange, arguments: Map<String, Any>): McpSchema.CallToolResult =
        ToolSupport.execute(
            toolName = "list_accounts",
            arguments = emptyMap(),
            objectMapper = objectMapper,
            meterRegistry = meterRegistry,
            log = log,
            failureMessage = "Unable to list Telegram accounts",
        ) {
            val accounts = accountAccessPolicy.visibleAccounts()
            mapOf(
                "accounts" to accounts.map { mapOf("label" to it) },
                "multi_account" to registry.isMultiAccount(),
                "selection_required" to registry.isMultiAccount(),
            )
        }
}
