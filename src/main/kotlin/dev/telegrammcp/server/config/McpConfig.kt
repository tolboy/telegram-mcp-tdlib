package dev.telegrammcp.server.config

import dev.telegrammcp.server.client.TelegramAccountContext
import dev.telegrammcp.server.client.TelegramAccountRegistry
import dev.telegrammcp.server.exception.ReadOnlyModeException
import dev.telegrammcp.server.model.AuditOutcome
import dev.telegrammcp.server.security.AccountAccessPolicy
import dev.telegrammcp.server.service.AuditService
import dev.telegrammcp.server.service.DestructiveApprovalService
import dev.telegrammcp.server.service.OperationGuardService
import dev.telegrammcp.server.service.ToolSurfacePolicy
import dev.telegrammcp.server.tool.AccountAgnosticMcpToolHandler
import dev.telegrammcp.server.tool.McpToolHandler
import dev.telegrammcp.server.tool.ToolSupport
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification
import io.modelcontextprotocol.spec.McpSchema
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Registers all [McpToolHandler] beans as MCP sync tools.
 *
 * Each handler provides its own [McpSchema.Tool] definition and an execution
 * function. This configuration bridges the Spring component model with the
 * MCP server auto-configuration from Spring AI.
 */
@Configuration
class McpConfig {

    private val log = LoggerFactory.getLogger(McpConfig::class.java)

    /**
     * Auto-discovers every [McpToolHandler] in the context, converts each one
     * into a [SyncToolSpecification], and returns them as a list so the Spring
     * AI MCP auto-configuration can register them with the server.
     */
    @Bean
    fun syncToolSpecifications(
        handlers: List<McpToolHandler>,
        registry: TelegramAccountRegistry,
        accountContext: TelegramAccountContext,
        accountAccessPolicy: AccountAccessPolicy,
        serverMode: ServerModeProperties,
        toolSurfacePolicy: ToolSurfacePolicy,
        auditService: AuditService,
        approvalService: DestructiveApprovalService,
    ): List<SyncToolSpecification> {
        val definitions = handlers.map { handler -> handler to handler.definition() }
        val duplicateNames = definitions
            .groupingBy { (_, tool) -> tool.name() }
            .eachCount()
            .filterValues { count -> count > 1 }
            .keys
        require(duplicateNames.isEmpty()) {
            "Duplicate MCP tool names: ${duplicateNames.sorted().joinToString()}"
        }
        toolSurfacePolicy.validateConfiguredNames(definitions.map { (_, tool) -> tool.name() })

        val visibleDefinitions = definitions.filter { (_, tool) ->
            toolSurfacePolicy.isVisible(tool.name(), serverMode.readOnly)
        }
        val hiddenCount = definitions.size - visibleDefinitions.size

        return visibleDefinitions.map { (handler, definition) ->
            val tool = withBehaviorAnnotations(
                withAccountSelector(definition, handler is AccountAgnosticMcpToolHandler, registry),
                serverMode,
            )
            log.info("Registering MCP tool: {} — {}", tool.name(), tool.description())
            SyncToolSpecification(tool) { exchange, request ->
                val arguments = request.arguments() ?: emptyMap()
                // Once per session, and on any tool: an operator should learn
                // that approval cannot be requested before a destructive call
                // fails, not from the failure itself.
                approvalService.warnIfClientCannotApprove(exchange)
                when {
                    // Defense in depth: read-only mode already hides write
                    // tools at registration, but a client may replay a cached
                    // tool list. Execution must fail closed regardless.
                    serverMode.readOnly && tool.name() in OperationGuardService.WRITE_TOOLS ->
                        auditService.executeWithFallbackAudit(
                            toolName = tool.name(),
                            arguments = arguments,
                            errorOutcome = AuditOutcome.BLOCKED_READONLY,
                        ) {
                            ToolSupport.errorResult(ReadOnlyModeException(tool.name()))
                        }

                    handler is AccountAgnosticMcpToolHandler ->
                        auditService.executeWithFallbackAudit(tool.name(), arguments) {
                            approvalService.requireApproval(exchange, tool.name(), arguments)
                            handler.execute(exchange, arguments)
                        }

                    else -> {
                        val routedArguments = arguments - AccountAccessPolicy.ACCOUNT_ARGUMENT
                        val selectionStarted = System.nanoTime()
                        val account = try {
                            accountAccessPolicy.selectAccount(arguments)
                        } catch (error: Exception) {
                            auditService.record(
                                toolName = tool.name(),
                                arguments = routedArguments,
                                outcome = AuditService.outcomeFor(error),
                                error = error.message,
                                durationMs = (System.nanoTime() - selectionStarted) / 1_000_000,
                                accountOverride = requestedKnownAccount(arguments, registry),
                            )
                            throw error
                        }
                        accountContext.withAccount(account) {
                            auditService.executeWithFallbackAudit(tool.name(), routedArguments) {
                                // Ahead of the handler, so no Telegram call can
                                // precede the operator's answer. Central rather
                                // than per-tool: a new destructive tool inherits
                                // the gate instead of having to remember it. The
                                // approval prompt sees the original selector so
                                // the operator knows which account is affected;
                                // handlers and audit still receive routed args.
                                approvalService.requireApproval(exchange, tool.name(), arguments)
                                handler.execute(exchange, routedArguments)
                            }
                        }
                    }
                }
            }
        }.also {
            if (!serverMode.readOnly && toolSurfacePolicy.profile == McpToolProfile.ALL) {
                log.warn(
                    "FULL WRITE SURFACE ENABLED: MCP_TOOL_PROFILE=all with MCP_READ_ONLY=false exposes every registered Telegram operation",
                )
            }
            if (approvalService.isEnabled()) {
                log.info(
                    "Destructive operations require human approval ({} mode): {}",
                    serverMode.confirmation.approval.name.lowercase(),
                    approvalService.describeApprovalRoute(),
                )
            }
            log.info(
                "Registered {} MCP tool(s) for {} profile; hid {} tool(s) by surface policy",
                it.size,
                toolSurfacePolicy.profile.name.lowercase(),
                hiddenCount,
            )
        }
    }

    private fun requestedKnownAccount(
        arguments: Map<String, Any>,
        registry: TelegramAccountRegistry,
    ): String? = arguments[AccountAccessPolicy.ACCOUNT_ARGUMENT]
        ?.toString()
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.let { requested ->
            runCatching { TelegramAccountRegistry.normalizeLabel(requested) }
                .getOrNull()
                ?.takeIf(registry::has)
        }

    private fun withBehaviorAnnotations(
        tool: McpSchema.Tool,
        serverMode: ServerModeProperties,
    ): McpSchema.Tool = McpSchema.Tool(
        tool.name(),
        tool.title(),
        tool.description(),
        tool.inputSchema(),
        tool.outputSchema(),
        OperationGuardService.annotationsFor(
            tool.name(),
            serverMode.confirmation.destructiveTools,
        ),
        tool.meta(),
        tool.icons(),
    )

    private fun withAccountSelector(
        tool: McpSchema.Tool,
        accountAgnostic: Boolean,
        registry: TelegramAccountRegistry,
    ): McpSchema.Tool {
        if (accountAgnostic) return tool

        val schema = tool.inputSchema().toMutableMap()
        @Suppress("UNCHECKED_CAST")
        val properties = (schema["properties"] as? Map<String, Any>).orEmpty().toMutableMap()
        properties.putIfAbsent(
            AccountAccessPolicy.ACCOUNT_ARGUMENT,
            mapOf(
                "type" to "string",
                "enum" to registry.labels(),
                "description" to AccountAccessPolicy.ACCOUNT_DESCRIPTION,
            ),
        )
        schema["properties"] = properties

        @Suppress("UNCHECKED_CAST")
        val required = (schema["required"] as? List<String>).orEmpty().toMutableList()
        if (registry.isMultiAccount() && AccountAccessPolicy.ACCOUNT_ARGUMENT !in required) {
            required += AccountAccessPolicy.ACCOUNT_ARGUMENT
        }
        schema["required"] = required

        return McpSchema.Tool(
            tool.name(),
            tool.title(),
            tool.description(),
            schema,
            tool.outputSchema(),
            tool.annotations(),
            tool.meta(),
            tool.icons(),
        )
    }
}
