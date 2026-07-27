package dev.telegrammcp.server.service

import dev.telegrammcp.server.config.ServerModeProperties
import dev.telegrammcp.server.exception.ApprovalDeniedException
import dev.telegrammcp.server.exception.ApprovalUnavailableException
import dev.telegrammcp.server.util.StructuredLogger
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import org.springframework.stereotype.Service

/**
 * Obtains human approval for a destructive operation before it runs.
 *
 * The `confirmed: true` argument is a caller acknowledgement: it travels in the
 * tool call, so the model can set it unprompted — including when a message it
 * just read told it to. That makes it defense in depth, never evidence that a
 * person agreed.
 *
 * Elicitation closes that gap because it inverts the direction. The server asks
 * the host, the host asks the person, and the answer returns over the protocol
 * rather than through the model's turn. A prompt-injection payload can make the
 * model *request* a ban; it cannot make the human accept one.
 */
@Service
class DestructiveApprovalService(
    private val props: ServerModeProperties,
    private val operationGuardService: OperationGuardService,
) {

    private val log = StructuredLogger.forClass<DestructiveApprovalService>()

    /** True when any tool call could be gated, so callers can skip the work entirely. */
    fun isEnabled(): Boolean = props.confirmation.approval != ServerModeProperties.ApprovalMode.OFF

    /**
     * Blocks until the operator answers, and returns normally only on approval.
     *
     * @throws ApprovalUnavailableException when nobody can be asked
     * @throws ApprovalDeniedException when the answer is no, or never comes
     */
    fun requireApproval(
        exchange: McpSyncServerExchange?,
        toolName: String,
        arguments: Map<String, Any>,
    ) {
        if (!isEnabled()) return
        if (!operationGuardService.isDestructiveTool(toolName)) return

        // No exchange and no advertised capability are the same situation: this
        // session has no channel to a human. Fail rather than fall back to the
        // acknowledgement this mode exists to replace.
        if (exchange?.clientCapabilities?.elicitation() == null) {
            log.warn("Destructive tool '{}' blocked — client cannot be asked for approval", toolName)
            throw ApprovalUnavailableException(toolName)
        }

        val result = try {
            exchange.createElicitation(
                McpSchema.ElicitRequest.builder(
                    approvalPrompt(toolName, arguments),
                    // No fields to fill in: the accept/decline action is the
                    // entire answer, and an empty schema keeps hosts from
                    // rendering a form where a yes/no belongs.
                    mapOf("type" to "object", "properties" to emptyMap<String, Any>()),
                ).build(),
            )
        } catch (error: Exception) {
            // A transport failure, a timeout, or a host that advertised the
            // capability without implementing it all mean the same thing: no
            // approval was obtained.
            log.warn("Approval request for '{}' failed: {}", toolName, error.message)
            throw ApprovalDeniedException(toolName, "the approval request could not be completed")
        }

        when (result.action()) {
            McpSchema.ElicitResult.Action.ACCEPT ->
                log.info("Destructive tool '{}' approved by the operator", toolName)
            McpSchema.ElicitResult.Action.DECLINE ->
                throw ApprovalDeniedException(toolName, "the operator declined")
            McpSchema.ElicitResult.Action.CANCEL ->
                throw ApprovalDeniedException(toolName, "the operator dismissed the request")
            else ->
                throw ApprovalDeniedException(toolName, "the approval response was not understood")
        }
    }

    /**
     * Describes the operation in the terms the person will be deciding about.
     *
     * The prompt names the target rather than only the tool: "ban_user" is not
     * something an operator can weigh, but a user id in a specific chat is.
     */
    private fun approvalPrompt(toolName: String, arguments: Map<String, Any>): String {
        val target = TARGET_ARGUMENTS
            .mapNotNull { key -> arguments[key]?.toString()?.trim()?.takeIf(String::isNotEmpty)?.let { key to it } }
            .joinToString(", ") { (key, value) -> "$key=$value" }
        val subject = if (target.isEmpty()) toolName else "$toolName ($target)"
        return "Approve destructive Telegram operation: $subject. " +
            "This was requested by an AI assistant and cannot be undone by this server."
    }

    private companion object {
        /**
         * Arguments worth showing an operator, most identifying first. Message
         * text is deliberately absent: it can be attacker-controlled content and
         * has no place in the dialog that decides whether to trust the call.
         */
        private val TARGET_ARGUMENTS = listOf("account", "chat_id", "user_id", "message_id", "link")
    }
}
