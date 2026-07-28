package dev.telegrammcp.server.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Configuration for server operational modes and security controls.
 *
 * Controls read-only mode, confirmation requirements for destructive operations,
 * file security (allowed roots), and audit logging.
 */
@ConfigurationProperties(prefix = "server-mode")
data class ServerModeProperties(
    /** When true, all write/mutating tools are blocked. */
    val readOnly: Boolean = false,

    /** Configuration for destructive-operation caller acknowledgement. */
    val confirmation: ConfirmationProps = ConfirmationProps(),

    /** File system security settings. */
    val fileSecurity: FileSecurityProps = FileSecurityProps(),

    /** Audit logging settings. */
    val audit: AuditProps = AuditProps(),
) {
    data class ConfirmationProps(
        /**
         * When true, destructive tools require "confirmed": true in arguments.
         * This is a caller acknowledgement, not cryptographic proof of human approval.
         */
        val enabled: Boolean = false,

        /**
         * Tool names that are considered destructive and require confirmation.
         * Defaults are applied in [dev.telegrammcp.server.service.OperationGuardService].
         */
        val destructiveTools: List<String> = emptyList(),

        /**
         * How a destructive operation obtains human approval. [ApprovalMode.OFF]
         * keeps the caller acknowledgement above as the only gate.
         */
        val approval: ApprovalMode = ApprovalMode.OFF,

        /**
         * How long a person has to answer before the operation is refused.
         * Refusing on silence is the safe direction, so this is a deadline
         * rather than an indefinite wait.
         */
        val approvalTimeout: Duration = Duration.ofSeconds(120),
    )

    /**
     * Where the answer to "may this destructive operation proceed?" comes from.
     *
     * Every mode except [OFF] gets the answer over a channel the model does not
     * write to. That is the whole distinction from `confirmed: true`, which
     * travels inside the tool call the model itself composed.
     */
    enum class ApprovalMode {
        /** Only the caller-asserted `confirmed: true`. A model can set that itself. */
        OFF,

        /**
         * Ask the person operating the MCP host, over the protocol's elicitation
         * channel. Clean when the client implements it — and many do not, in
         * which case destructive tools are refused rather than downgraded.
         */
        ELICITATION,

        /**
         * Ask over a loopback page the server hosts itself. Works with every
         * client, including those that never negotiated elicitation, because it
         * does not depend on the client at all.
         */
        LOOPBACK,

        /**
         * Elicitation when the connected client offers it, loopback otherwise.
         * Not a downgrade: both answers come from a person over a channel the
         * model cannot reach, so the choice is only about which one the client
         * can actually render.
         */
        AUTO,
    }

    data class FileSecurityProps(
        /**
         * Allowed root directories for file operations.
         * Paths outside these roots are rejected. Empty = deny all file ops.
         */
        val allowedRoots: List<String> = emptyList(),

        /** Maximum allowed file size in bytes (default: 200MB). */
        val maxFileSizeBytes: Long = 200 * 1024 * 1024,

        /**
         * Allowed file extensions for upload operations.
         * Empty = all extensions allowed (within allowed roots).
         */
        val allowedExtensions: List<String> = emptyList(),
    )

    data class AuditProps(
        /** When true, all tool invocations are logged to the audit log. */
        val enabled: Boolean = true,

        /** When true, tool arguments are included in audit entries (may contain PII). */
        val logArguments: Boolean = false,

        /**
         * Optional append-only JSONL file for a durable local audit trail.
         * Blank keeps console logging, metrics, and the in-memory ring only.
         */
        val file: String = "",
    )
}
