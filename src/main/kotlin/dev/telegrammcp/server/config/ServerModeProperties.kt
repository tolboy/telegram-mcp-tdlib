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

    /** Configuration for destructive operation confirmation. */
    val confirmation: ConfirmationProps = ConfirmationProps(),

    /** File system security settings. */
    val fileSecurity: FileSecurityProps = FileSecurityProps(),

    /** Audit logging settings. */
    val audit: AuditProps = AuditProps(),
) {
    data class ConfirmationProps(
        /** When true, destructive tools require "confirmed": true in arguments. */
        val enabled: Boolean = false,

        /**
         * Tool names that are considered destructive and require confirmation.
         * Defaults are applied in [dev.telegrammcp.server.service.OperationGuardService].
         */
        val destructiveTools: List<String> = emptyList(),
    )

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
    )
}
