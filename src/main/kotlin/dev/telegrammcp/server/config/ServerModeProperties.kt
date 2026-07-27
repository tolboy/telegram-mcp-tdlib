package dev.telegrammcp.server.config

import org.springframework.boot.context.properties.ConfigurationProperties

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
    )

    /**
     * Where the answer to "may this destructive operation proceed?" comes from.
     */
    enum class ApprovalMode {
        /** Only the caller-asserted `confirmed: true`. A model can set that itself. */
        OFF,

        /**
         * Ask the person operating the MCP host, over the protocol's elicitation
         * channel. The model neither sees nor writes the response, so it cannot
         * approve on the human's behalf.
         */
        ELICITATION,
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
