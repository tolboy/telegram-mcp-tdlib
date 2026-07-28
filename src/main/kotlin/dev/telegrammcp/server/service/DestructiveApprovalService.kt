package dev.telegrammcp.server.service

import dev.telegrammcp.server.config.ServerModeProperties
import dev.telegrammcp.server.config.ServerModeProperties.ApprovalMode
import dev.telegrammcp.server.exception.ApprovalDeniedException
import dev.telegrammcp.server.exception.ApprovalUnavailableException
import dev.telegrammcp.server.util.StructuredLogger
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import jakarta.annotation.PreDestroy
import org.springframework.stereotype.Service
import java.util.Collections

/**
 * Obtains human approval for a destructive operation before it runs.
 *
 * The `confirmed: true` argument is a caller acknowledgement: it travels in the
 * tool call, so the model can set it unprompted — including when a message it
 * just read told it to. That makes it defense in depth, never evidence that a
 * person agreed.
 *
 * Real approval inverts the direction. The question goes out over a channel the
 * model does not write to and the answer comes back the same way, so a
 * prompt-injection payload can make the model *request* a ban but cannot accept
 * one. Two channels do that: the client's own elicitation prompt, and a loopback
 * page this server hosts. The second exists because the first is not widely
 * implemented — Claude Desktop advertises no elicitation capability — and an
 * approval guarantee that only works on some clients is not one worth relying on.
 */
@Service
class DestructiveApprovalService(
    private val props: ServerModeProperties,
    private val operationGuardService: OperationGuardService,
    injectedLoopbackServer: LoopbackApprovalServer? = null,
) {

    /**
     * Built on first use, not at startup: the approval timeout is only binding
     * for this route, so a deployment that never asks over loopback must not be
     * refused a start over a value it does not use.
     */
    private val loopbackServer: Lazy<LoopbackApprovalServer> = injectedLoopbackServer
        ?.let { lazyOf(it) }
        ?: lazy { LoopbackApprovalServer(props.confirmation.approvalTimeout) }

    private val log = StructuredLogger.forClass<DestructiveApprovalService>()

    /** Sessions already told that their client cannot render an elicitation prompt. */
    private val warnedSessions: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    /** True when any tool call could be gated, so callers can skip the work entirely. */
    fun isEnabled(): Boolean = props.confirmation.approval != ApprovalMode.OFF

    /**
     * Where the operator should expect the question, for the startup log.
     *
     * Naming the wrong route is worse than naming none: an operator who is told
     * to watch a host prompt that never appears will read the resulting refusals
     * as a Telegram fault.
     */
    fun describeApprovalRoute(): String = when (props.confirmation.approval) {
        ApprovalMode.OFF -> "no approval is requested"
        ApprovalMode.ELICITATION ->
            "asked through the MCP host; clients without the elicitation capability cannot run them"
        ApprovalMode.LOOPBACK ->
            "asked on a loopback page, with the link written to stderr"
        ApprovalMode.AUTO ->
            "asked through the MCP host when it supports elicitation, otherwise on a loopback page " +
                "whose link is written to stderr"
    }

    /**
     * Reports a client that cannot answer an elicitation prompt, once per session.
     *
     * Without this the operator learns of the mismatch from a failed `ban_user`,
     * which is both late and easy to misread as a Telegram error rather than a
     * configuration one.
     */
    fun warnIfClientCannotApprove(exchange: McpSyncServerExchange?) {
        if (props.confirmation.approval != ApprovalMode.ELICITATION) return
        if (exchange == null || supportsElicitation(exchange)) return
        val session = runCatching { exchange.sessionId() }.getOrNull() ?: return
        if (!warnedSessions.add(session)) return
        log.warn(
            "Client '{}' did not advertise the elicitation capability, so destructive tools cannot be " +
                "approved and will be refused. Set MCP_DESTRUCTIVE_APPROVAL=auto to ask over a loopback " +
                "page instead.",
            runCatching { exchange.clientInfo?.name() }.getOrNull() ?: "unknown",
        )
    }

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

        val description = describe(arguments)
        when (props.confirmation.approval) {
            ApprovalMode.OFF -> return
            ApprovalMode.ELICITATION -> requireElicitation(exchange, toolName, description)
            ApprovalMode.LOOPBACK -> requireLoopback(toolName, description)
            ApprovalMode.AUTO ->
                if (exchange != null && supportsElicitation(exchange)) {
                    requireElicitation(exchange, toolName, description)
                } else {
                    requireLoopback(toolName, description)
                }
        }
        log.info("Destructive tool '{}' approved by the operator", toolName)
    }

    private fun requireElicitation(
        exchange: McpSyncServerExchange?,
        toolName: String,
        description: String,
    ) {
        if (exchange == null || !supportsElicitation(exchange)) {
            log.warn("Destructive tool '{}' blocked — client cannot be asked for approval", toolName)
            throw ApprovalUnavailableException(toolName)
        }

        val result = try {
            exchange.createElicitation(
                // The record constructor rather than the builder: both builder
                // overloads are deprecated in this SDK version.
                McpSchema.ElicitFormRequest(
                    approvalPrompt(toolName, description),
                    // No fields to fill in: the accept/decline action is the
                    // entire answer, and an empty schema keeps hosts from
                    // rendering a form where a yes/no belongs.
                    mapOf("type" to "object", "properties" to emptyMap<String, Any>()),
                    emptyMap(),
                ),
            )
        } catch (error: Exception) {
            // A transport failure, a timeout, or a host that advertised the
            // capability without implementing it all mean the same thing: no
            // approval was obtained.
            log.warn("Approval request for '{}' failed: {}", toolName, error.message)
            throw ApprovalDeniedException(toolName, "the approval request could not be completed")
        }

        when (result.action()) {
            McpSchema.ElicitResult.Action.ACCEPT -> Unit
            McpSchema.ElicitResult.Action.DECLINE ->
                throw ApprovalDeniedException(toolName, "the operator declined")
            McpSchema.ElicitResult.Action.CANCEL ->
                throw ApprovalDeniedException(toolName, "the operator dismissed the request")
            else ->
                throw ApprovalDeniedException(toolName, "the approval response was not understood")
        }
    }

    private fun requireLoopback(toolName: String, description: String) {
        val result = try {
            loopbackServer.value.requestApproval(toolName, description)
        } catch (error: ApprovalEndpointException) {
            log.warn("Destructive tool '{}' blocked — loopback approval is unavailable: {}", toolName, error.message)
            throw ApprovalUnavailableException(
                toolName,
                "the local loopback approval endpoint could not be started",
                "Check local socket permissions and IPv4 loopback availability, or use " +
                    "MCP_DESTRUCTIVE_APPROVAL=elicitation with a capable client.",
                error,
            )
        }
        when (result) {
            LoopbackApprovalServer.ApprovalResult.APPROVED -> Unit
            LoopbackApprovalServer.ApprovalResult.DENIED ->
                throw ApprovalDeniedException(toolName, "the operator declined")
            LoopbackApprovalServer.ApprovalResult.TIMED_OUT ->
                throw ApprovalDeniedException(
                    toolName,
                    "the operator did not answer within ${props.confirmation.approvalTimeout.toSeconds()}s",
                )
            LoopbackApprovalServer.ApprovalResult.UNAVAILABLE ->
                throw ApprovalUnavailableException(
                    toolName,
                    "the local loopback approval endpoint is closed or shutting down",
                    "Retry only after the server has restarted cleanly.",
                )
        }
    }

    private fun supportsElicitation(exchange: McpSyncServerExchange): Boolean =
        runCatching { exchange.clientCapabilities?.elicitation() != null }.getOrDefault(false)

    private fun approvalPrompt(toolName: String, description: String): String =
        "Approve destructive Telegram operation: $toolName ($description). " +
            "This was requested by an AI assistant and cannot be undone by this server."

    /**
     * Describes the target in the terms the person will be deciding about.
     *
     * A tool name alone is not something an operator can weigh; a user id in a
     * specific chat is. Message text is deliberately absent: it can be
     * attacker-controlled content arriving from the very chat that provoked the
     * call, and it has no place in the dialog that decides whether to trust it.
     */
    private fun describe(arguments: Map<String, Any>): String {
        val target = TARGET_ARGUMENTS
            .mapNotNull { key ->
                arguments[key]
                    ?.toString()
                    ?.let(::normalizeForDisplay)
                    ?.takeIf(String::isNotEmpty)
                    ?.let { value ->
                        if (key in LINK_ARGUMENTS) "$key=${redactLink(value)}" else "$key=$value"
                    }
            }
            .joinToString(", ")
        return target.ifEmpty { "no identifying arguments" }
    }

    /**
     * Shows enough of an invite link to recognise it, and not enough to use it.
     *
     * `join_chat_by_link` has no other identifying argument, so hiding the value
     * outright leaves the operator approving a chat they cannot name. The host
     * and path stay; only the trailing secret is cut, which is the part that
     * would otherwise let anyone reading the log or the terminal join too.
     */
    private fun redactLink(value: String): String {
        val withoutScheme = value.substringAfter("://", value)
        val path = withoutScheme.takeWhile { it != '?' && it != '#' }
        val lastSlash = path.lastIndexOf('/')
        val prefix = path.substring(0, lastSlash + 1)
        val token = path.substring(lastSlash + 1)
        val shown = token.take(LINK_TOKEN_PREFIX_LENGTH)
        return if (shown.length < token.length) "$prefix$shown…" else "$prefix$shown"
    }

    /**
     * Approval descriptions reach stderr and HTML. Collapse control/format
     * characters so an argument cannot forge terminal lines or reorder text,
     * and bound each displayed value independently.
     */
    private fun normalizeForDisplay(value: String): String = value
        .map { character ->
            when (Character.getType(character)) {
                Character.CONTROL.toInt(),
                Character.FORMAT.toInt(),
                Character.LINE_SEPARATOR.toInt(),
                Character.PARAGRAPH_SEPARATOR.toInt(),
                -> ' '
                else -> character
            }
        }
        .joinToString("")
        .trim()
        .replace(WHITESPACE, " ")
        .take(MAX_DISPLAY_VALUE_LENGTH)

    @PreDestroy
    fun shutdown() {
        // Never force the endpoint into existence just to close it.
        if (loopbackServer.isInitialized()) loopbackServer.value.close()
    }

    private companion object {
        /** Arguments worth showing an operator, most identifying first. */
        private val TARGET_ARGUMENTS = listOf(
            "account",
            "chat_id",
            "user_id",
            "message_id",
            "profile_photo_id",
            "folder_id",
            // The only target `create_channel` and `create_supergroup` have.
            // Unlike message text it is composed by the caller rather than
            // arriving from a chat, and it is normalized before it is shown.
            "title",
            "link",
            "invite_link",
        )

        /** Values that identify a chat and authorise joining it in one string. */
        private val LINK_ARGUMENTS = setOf("link", "invite_link")
        private val WHITESPACE = Regex("\\s+")
        private const val MAX_DISPLAY_VALUE_LENGTH = 256
        private const val LINK_TOKEN_PREFIX_LENGTH = 8
    }
}
