package dev.telegrammcp.server.model

import java.time.Instant

/**
 * Represents a single audit log entry for tool invocations.
 */
data class AuditEntry(
    val timestamp: Instant,
    val toolName: String,
    val category: AuditCategory,
    val arguments: Map<String, Any>,
    val traceId: String?,
    val sessionId: String?,
    /** Stable routing label, never a Telegram profile name or phone number. */
    val account: String? = null,
    val outcome: AuditOutcome,
    val errorMessage: String? = null,
    val durationMs: Long? = null,
)

/**
 * Category of the audited operation.
 */
enum class AuditCategory {
    READ_MESSAGE,
    SEND_MESSAGE,
    EDIT_MESSAGE,
    READ_CHAT,
    MANAGE_CHAT,
    READ_CONTACT,
    MANAGE_CONTACT,
    READ_USER,
    MEDIA_DOWNLOAD,
    MEDIA_UPLOAD,
    ENTITY_RESOLUTION,
    REACT_MESSAGE,
    POLL,
    INLINE_INTERACTION,
    FORUM_TOPIC,
    STICKER,
    VOICE,
    DRAFT,
    PROFILE,
    ADMIN_LOG,

    /** Read-only discovery and query-based search over public chats. */
    PUBLIC_SEARCH_READ,

    /** Server/connector capability metadata that does not access Telegram content. */
    SERVER_METADATA,

    /** Safety fallback; production tool inventory tests reject this for registered tools. */
    UNCLASSIFIED,
}

/**
 * Outcome of the audited operation.
 */
enum class AuditOutcome {
    SUCCESS,
    ERROR,
    BLOCKED_READONLY,
    BLOCKED_CONFIRMATION,
    BLOCKED_GUARDRAIL,
    BLOCKED_ANTISPAM,

    /** A human declined the operation, or approval could not be requested. */
    BLOCKED_APPROVAL,
}
