package dev.telegrammcp.server.service

import com.fasterxml.jackson.databind.ObjectMapper
import dev.telegrammcp.server.client.TelegramAccountContext
import dev.telegrammcp.server.config.ServerModeProperties
import dev.telegrammcp.server.exception.AccountAccessDeniedException
import dev.telegrammcp.server.exception.AntiSpamException
import dev.telegrammcp.server.exception.ApprovalDeniedException
import dev.telegrammcp.server.exception.ApprovalUnavailableException
import dev.telegrammcp.server.exception.ChatNotAllowedException
import dev.telegrammcp.server.exception.ConfirmationRequiredException
import dev.telegrammcp.server.exception.FileSecurityException
import dev.telegrammcp.server.exception.GuardrailViolationException
import dev.telegrammcp.server.exception.ReadOnlyModeException
import dev.telegrammcp.server.model.AuditCategory
import dev.telegrammcp.server.model.AuditEntry
import dev.telegrammcp.server.model.AuditOutcome
import dev.telegrammcp.server.util.StructuredLogger
import io.micrometer.core.instrument.MeterRegistry
import io.modelcontextprotocol.spec.McpSchema
import org.slf4j.MDC
import org.springframework.stereotype.Service
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Audit logging service for all MCP tool invocations.
 *
 * Every tool call is recorded with:
 * - **What** — tool name, category, arguments (if configured)
 * - **Who** — traceId, sessionId from MDC
 * - **When** — timestamp and execution duration
 * - **Result** — success, error, or blocked by policy
 *
 * Audit entries are:
 * 1. Logged via the structured logger (appears in both console and JSON output)
 * 2. Stored in a bounded in-memory ring buffer (last 1000 entries)
 * 3. Counted via Micrometer metrics (`mcp.audit.operations` counter)
 * 4. Optionally appended to a forced JSONL file when `server-mode.audit.file`
 *    is configured
 */
@Service
class AuditService(
    private val props: ServerModeProperties,
    private val meterRegistry: MeterRegistry,
    private val objectMapper: ObjectMapper,
    private val accountContext: TelegramAccountContext? = null,
) {

    private val log = StructuredLogger.forClass<AuditService>()

    /** In-memory ring buffer of recent audit entries (bounded to 1000). */
    private val recentEntries = ConcurrentLinkedDeque<AuditEntry>()
    private val auditFile: Path? = props.audit.file.trim()
        .takeIf { it.isNotEmpty() }
        ?.let { Path.of(it).toAbsolutePath().normalize() }
    private val fileWriteLock = Any()
    private val invocationScope = ThreadLocal<InvocationScope?>()

    companion object {
        private const val MAX_ENTRIES = 1000
        private const val MAX_ERROR_LENGTH = 2_048
        private const val REDACTED = "***REDACTED***"
        private val SENSITIVE_KEY_TOKENS = listOf("password", "secret", "token", "api_key", "apikey", "phone")

        /** Maps tool names to audit categories. */
        private val TOOL_CATEGORIES = mapOf(
            // Read messages
            "get_history" to AuditCategory.READ_MESSAGE,
            "get_messages" to AuditCategory.READ_MESSAGE,
            "search_messages" to AuditCategory.READ_MESSAGE,
            "search_global" to AuditCategory.READ_MESSAGE,
            "get_pinned_messages" to AuditCategory.READ_MESSAGE,
            "export_chat_history" to AuditCategory.READ_MESSAGE,
            "get_message_link" to AuditCategory.READ_MESSAGE,
            "get_message_viewers" to AuditCategory.READ_MESSAGE,

            // Send/edit messages
            "send_message" to AuditCategory.SEND_MESSAGE,
            "reply_to_message" to AuditCategory.SEND_MESSAGE,
            "forward_message" to AuditCategory.SEND_MESSAGE,
            "edit_message" to AuditCategory.EDIT_MESSAGE,
            "delete_message" to AuditCategory.EDIT_MESSAGE,
            "pin_message" to AuditCategory.EDIT_MESSAGE,
            "unpin_message" to AuditCategory.EDIT_MESSAGE,
            "mark_as_read" to AuditCategory.EDIT_MESSAGE,
            "list_scheduled_messages" to AuditCategory.READ_MESSAGE,
            "schedule_message" to AuditCategory.SEND_MESSAGE,
            "reschedule_message" to AuditCategory.EDIT_MESSAGE,
            "cancel_scheduled_message" to AuditCategory.EDIT_MESSAGE,

            // Read chats
            "list_chats" to AuditCategory.READ_CHAT,
            "get_chat" to AuditCategory.READ_CHAT,
            "get_participants" to AuditCategory.READ_CHAT,
            "get_common_chats" to AuditCategory.READ_CHAT,

            // Manage chats
            "create_group" to AuditCategory.MANAGE_CHAT,
            "invite_to_group" to AuditCategory.MANAGE_CHAT,
            "leave_chat" to AuditCategory.MANAGE_CHAT,
            "ban_user" to AuditCategory.MANAGE_CHAT,
            "unban_user" to AuditCategory.MANAGE_CHAT,
            "promote_admin" to AuditCategory.MANAGE_CHAT,
            "demote_admin" to AuditCategory.MANAGE_CHAT,
            "edit_chat_title" to AuditCategory.MANAGE_CHAT,
            "archive_chat" to AuditCategory.MANAGE_CHAT,
            "unarchive_chat" to AuditCategory.MANAGE_CHAT,
            "mute_chat" to AuditCategory.MANAGE_CHAT,
            "unmute_chat" to AuditCategory.MANAGE_CHAT,
            "list_chat_folders" to AuditCategory.READ_CHAT,
            "get_chat_folder" to AuditCategory.READ_CHAT,
            "configure_chat_folder" to AuditCategory.MANAGE_CHAT,
            "delete_chat_folder" to AuditCategory.MANAGE_CHAT,
            "reorder_chat_folders" to AuditCategory.MANAGE_CHAT,
            "get_group_permissions" to AuditCategory.READ_CHAT,
            "set_group_permissions" to AuditCategory.MANAGE_CHAT,
            "set_member_permissions" to AuditCategory.MANAGE_CHAT,
            "set_admin_rights" to AuditCategory.MANAGE_CHAT,
            "set_chat_description" to AuditCategory.MANAGE_CHAT,
            "set_slow_mode" to AuditCategory.MANAGE_CHAT,
            "set_forum_topics_enabled" to AuditCategory.MANAGE_CHAT,

            // Account privacy and bot command menus
            "get_privacy_settings" to AuditCategory.PROFILE,
            "set_privacy_settings" to AuditCategory.PROFILE,
            "get_bot_commands" to AuditCategory.MANAGE_CHAT,
            "set_bot_commands" to AuditCategory.MANAGE_CHAT,

            // Read contacts/users
            "list_contacts" to AuditCategory.READ_CONTACT,
            "get_me" to AuditCategory.READ_USER,
            "resolve_username" to AuditCategory.ENTITY_RESOLUTION,

            // Manage contacts
            "add_contact" to AuditCategory.MANAGE_CONTACT,
            "delete_contact" to AuditCategory.MANAGE_CONTACT,
            "block_user" to AuditCategory.MANAGE_CONTACT,
            "unblock_user" to AuditCategory.MANAGE_CONTACT,

            // Media
            "download_media" to AuditCategory.MEDIA_DOWNLOAD,
            "get_media_info" to AuditCategory.MEDIA_DOWNLOAD,
            "send_file" to AuditCategory.MEDIA_UPLOAD,

            // Reactions & polls
            "send_reaction" to AuditCategory.REACT_MESSAGE,
            "remove_reaction" to AuditCategory.REACT_MESSAGE,
            "get_message_reactions" to AuditCategory.REACT_MESSAGE,
            "create_poll" to AuditCategory.POLL,
            "vote_poll" to AuditCategory.POLL,
            "close_poll" to AuditCategory.POLL,
            "get_message_context" to AuditCategory.READ_MESSAGE,

            // Inline buttons & forum topics
            "list_inline_buttons" to AuditCategory.INLINE_INTERACTION,
            "press_inline_button" to AuditCategory.INLINE_INTERACTION,
            "list_topics" to AuditCategory.FORUM_TOPIC,
            "create_topic" to AuditCategory.FORUM_TOPIC,
            "edit_forum_topic" to AuditCategory.FORUM_TOPIC,
            "close_forum_topic" to AuditCategory.FORUM_TOPIC,
            "reopen_forum_topic" to AuditCategory.FORUM_TOPIC,

            // Channels & invite links
            "create_channel" to AuditCategory.MANAGE_CHAT,
            "create_supergroup" to AuditCategory.MANAGE_CHAT,
            "get_invite_link" to AuditCategory.MANAGE_CHAT,
            "list_invite_links" to AuditCategory.READ_CHAT,
            "revoke_invite_link" to AuditCategory.MANAGE_CHAT,
            "join_chat_by_link" to AuditCategory.MANAGE_CHAT,
            "subscribe_public_channel" to AuditCategory.MANAGE_CHAT,
            "register_internal_chat" to AuditCategory.MANAGE_CHAT,
            "get_admins" to AuditCategory.READ_CHAT,
            "get_banned_users" to AuditCategory.READ_CHAT,

            // Voice, stickers & chat photo
            "send_voice" to AuditCategory.VOICE,
            "transcribe_voice_note" to AuditCategory.VOICE,
            "send_sticker" to AuditCategory.STICKER,
            "get_sticker_sets" to AuditCategory.STICKER,
            "edit_chat_photo" to AuditCategory.MANAGE_CHAT,
            "delete_chat_photo" to AuditCategory.MANAGE_CHAT,

            // Drafts & profile
            "save_draft" to AuditCategory.DRAFT,
            "get_drafts" to AuditCategory.DRAFT,
            "clear_draft" to AuditCategory.DRAFT,
            "update_profile" to AuditCategory.PROFILE,
            "set_profile_photo" to AuditCategory.PROFILE,
            "delete_profile_photo" to AuditCategory.PROFILE,
            "get_user_photos" to AuditCategory.READ_USER,

            // Search, contacts & admin
            "search_public_chats" to AuditCategory.READ_CHAT,
            "search_contacts" to AuditCategory.READ_CONTACT,
            "get_blocked_users" to AuditCategory.READ_USER,
            "get_user_status" to AuditCategory.READ_USER,
            "get_recent_actions" to AuditCategory.ADMIN_LOG,

            // Utilities
            "message_from_link" to AuditCategory.READ_MESSAGE,
            "get_last_interaction" to AuditCategory.READ_MESSAGE,

            // Public-chat search — read-only discovery
            "discover_public_chats" to AuditCategory.PUBLIC_SEARCH_READ,
            "search_public_messages" to AuditCategory.PUBLIC_SEARCH_READ,

            // Server metadata
            "_manifest" to AuditCategory.SERVER_METADATA,
            "list_accounts" to AuditCategory.SERVER_METADATA,
        )

        internal fun categoryFor(toolName: String): AuditCategory =
            TOOL_CATEGORIES[toolName] ?: AuditCategory.UNCLASSIFIED

        /**
         * Converts policy exceptions into stable audit outcomes. Manual tool
         * handlers and the shared dispatcher must use the same taxonomy so a
         * blocked request is never reported as an operational failure.
         */
        internal fun outcomeFor(error: Exception): AuditOutcome = when (error) {
            is ReadOnlyModeException -> AuditOutcome.BLOCKED_READONLY
            is ConfirmationRequiredException -> AuditOutcome.BLOCKED_CONFIRMATION
            is ApprovalDeniedException,
            is ApprovalUnavailableException,
            -> AuditOutcome.BLOCKED_APPROVAL
            is AntiSpamException -> AuditOutcome.BLOCKED_ANTISPAM
            is GuardrailViolationException,
            is AccountAccessDeniedException,
            is ChatNotAllowedException,
            is FileSecurityException,
            -> AuditOutcome.BLOCKED_GUARDRAIL
            else -> AuditOutcome.ERROR
        }
    }

    /**
     * Records a tool invocation in the audit log.
     *
     * @param toolName   MCP tool name
     * @param arguments  tool input arguments (may be redacted based on config)
     * @param outcome    result of the invocation
     * @param error      error message if outcome is ERROR
     * @param durationMs execution time in milliseconds
     */
    fun record(
        toolName: String,
        arguments: Map<String, Any> = emptyMap(),
        outcome: AuditOutcome = AuditOutcome.SUCCESS,
        error: String? = null,
        durationMs: Long? = null,
        accountOverride: String? = null,
    ) {
        if (!props.audit.enabled) return
        invocationScope.get()
            ?.takeIf { it.toolName == toolName }
            ?.recorded = true

        val category = categoryFor(toolName)

        val sanitizedArgs = if (props.audit.logArguments) {
            sanitizeArguments(arguments)
        } else {
            // Identifiers and queries can contain usernames, phone numbers, or
            // private message text. Disabled means no argument values at all.
            emptyMap()
        }
        val sanitizedError = sanitizeError(error, outcome, arguments)

        val entry = AuditEntry(
            timestamp = Instant.now(),
            toolName = toolName,
            category = category,
            arguments = sanitizedArgs,
            traceId = MDC.get("traceId"),
            sessionId = MDC.get("sessionId"),
            account = accountOverride
                ?: runCatching { accountContext?.currentAccount() }.getOrNull(),
            outcome = outcome,
            errorMessage = sanitizedError,
            durationMs = durationMs,
        )

        val argumentsJson = runCatching { objectMapper.writeValueAsString(sanitizedArgs) }
            .getOrElse { "{}" }

        // Log the sanitized entry summary.
        when (outcome) {
            AuditOutcome.SUCCESS -> log.info(
                "AUDIT [{}] {} — {} ({}ms) args={}",
                category, toolName, outcome, durationMs ?: 0, argumentsJson,
            )
            AuditOutcome.ERROR -> log.warn(
                "AUDIT [{}] {} — {} — {} args={}",
                category, toolName, outcome, sanitizedError, argumentsJson,
            )
            AuditOutcome.BLOCKED_READONLY,
            AuditOutcome.BLOCKED_CONFIRMATION,
            AuditOutcome.BLOCKED_APPROVAL,
            AuditOutcome.BLOCKED_GUARDRAIL,
            AuditOutcome.BLOCKED_ANTISPAM,
            -> log.warn(
                "AUDIT [{}] {} — {} — blocked by policy{} args={}",
                category, toolName, outcome,
                sanitizedError?.let { ": $it" } ?: "",
                argumentsJson,
            )
        }

        appendDurableEntry(entry)

        // Store in ring buffer
        recentEntries.addFirst(entry)
        while (recentEntries.size > MAX_ENTRIES) {
            recentEntries.removeLast()
        }

        // Increment metrics counter
        meterRegistry.counter(
            "mcp.audit.operations",
            "tool", toolName,
            "category", category.name,
            "outcome", outcome.name,
        ).increment()
    }

    /**
     * Adds a dispatch-level fallback for handlers that do not record their own
     * outcome. A handler-level [record] marks this invocation as covered, so
     * existing detailed audit calls are preserved without duplicate entries.
     */
    fun executeWithFallbackAudit(
        toolName: String,
        arguments: Map<String, Any>,
        errorOutcome: AuditOutcome = AuditOutcome.ERROR,
        block: () -> McpSchema.CallToolResult,
    ): McpSchema.CallToolResult {
        if (!props.audit.enabled) return block()

        val previous = invocationScope.get()
        val scope = InvocationScope(toolName)
        invocationScope.set(scope)
        val startNanos = System.nanoTime()

        return try {
            val result = block()
            if (!scope.recorded) {
                record(
                    toolName = toolName,
                    arguments = arguments,
                    outcome = if (result.isError) errorOutcome else AuditOutcome.SUCCESS,
                    error = if (result.isError) "Tool returned an error result" else null,
                    durationMs = (System.nanoTime() - startNanos) / 1_000_000,
                )
            }
            result
        } catch (error: Exception) {
            if (!scope.recorded) {
                record(
                    toolName = toolName,
                    arguments = arguments,
                    outcome = outcomeFor(error),
                    error = error.message,
                    durationMs = (System.nanoTime() - startNanos) / 1_000_000,
                )
            }
            throw error
        } finally {
            if (previous == null) {
                invocationScope.remove()
            } else {
                invocationScope.set(previous)
            }
        }
    }

    /**
     * Returns the most recent audit entries (newest first).
     *
     * @param limit max entries to return
     */
    fun getRecentEntries(limit: Int = 100): List<AuditEntry> {
        return recentEntries.take(limit)
    }

    /**
     * Removes sensitive values from arguments before logging. Matching is
     * token-based ("bot_token", "proxyPassword") and recursive so credentials
     * nested inside object or array arguments cannot slip into the audit log.
     */
    private fun sanitizeArguments(args: Map<String, Any>): Map<String, Any> =
        args.mapValues { (key, value) -> sanitizeValue(key, value) }

    private fun sanitizeValue(key: String, value: Any?): Any = when {
        isSensitiveKey(key) -> REDACTED
        value is Map<*, *> -> value.entries.associate { (nestedKey, nestedValue) ->
            val name = nestedKey.toString()
            name to sanitizeValue(name, nestedValue)
        }
        value is List<*> -> value.map { element -> sanitizeValue(key = "", value = element) }
        else -> value ?: ""
    }

    private fun isSensitiveKey(key: String): Boolean {
        val normalized = key.lowercase()
        return SENSITIVE_KEY_TOKENS.any { it in normalized }
    }

    private fun sanitizeError(
        error: String?,
        outcome: AuditOutcome,
        arguments: Map<String, Any>,
    ): String? {
        if (error == null) return null
        if (!props.audit.logArguments) {
            return when (outcome) {
                AuditOutcome.ERROR -> "Tool execution failed; see application logs"
                AuditOutcome.BLOCKED_READONLY -> "Blocked by read-only policy"
                AuditOutcome.BLOCKED_CONFIRMATION -> "Blocked by caller-acknowledgement policy"
                AuditOutcome.BLOCKED_APPROVAL -> "Blocked by human-approval policy"
                AuditOutcome.BLOCKED_GUARDRAIL -> "Blocked by guardrail policy"
                AuditOutcome.BLOCKED_ANTISPAM -> "Blocked by anti-spam policy"
                AuditOutcome.SUCCESS -> null
            }
        }

        var sanitized = error.take(MAX_ERROR_LENGTH)
        sensitiveArgumentValues(arguments).forEach { secret ->
            if (secret.isNotEmpty()) sanitized = sanitized.replace(secret, REDACTED)
        }
        return sanitized
    }

    private fun sensitiveArgumentValues(arguments: Map<String, Any>): Set<String> = buildSet {
        fun collect(key: String, value: Any?) {
            when {
                isSensitiveKey(key) -> value?.toString()?.let(::add)
                value is Map<*, *> -> value.forEach { (nestedKey, nestedValue) ->
                    collect(nestedKey.toString(), nestedValue)
                }
                value is Iterable<*> -> value.forEach { collect("", it) }
                value is Array<*> -> value.forEach { collect("", it) }
            }
        }
        arguments.forEach { (key, value) -> collect(key, value) }
    }

    private fun appendDurableEntry(entry: AuditEntry) {
        val target = auditFile ?: return
        runCatching {
            target.parent?.let { Files.createDirectories(it) }
            prepareAuditFile(target)
            val bytes = StandardCharsets.UTF_8.encode(
                objectMapper.writeValueAsString(entry) + System.lineSeparator(),
            )
            synchronized(fileWriteLock) {
                FileChannel.open(
                    target,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND,
                    LinkOption.NOFOLLOW_LINKS,
                ).use { channel ->
                    while (bytes.hasRemaining()) channel.write(bytes)
                    channel.force(false)
                }
            }
        }.onFailure { error ->
            meterRegistry.counter("mcp.audit.durable_write_failures").increment()
            log.error("Unable to append durable audit entry to '{}': {}", target, error.message)
        }
    }

    private fun prepareAuditFile(target: Path) {
        synchronized(fileWriteLock) {
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    val ownerOnly = PosixFilePermissions.asFileAttribute(
                        setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    )
                    runCatching { Files.createFile(target, ownerOnly) }
                        .recoverCatching { Files.createFile(target) }
                        .getOrThrow()
                } catch (_: FileAlreadyExistsException) {
                    // Another process created the configured append target.
                }
            }
            require(!Files.isSymbolicLink(target)) {
                "Audit file must not be a symbolic link"
            }
            require(Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                "Audit file must be a regular file"
            }
            runCatching {
                Files.setPosixFilePermissions(
                    target,
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                )
            }
        }
    }

    private data class InvocationScope(
        val toolName: String,
        var recorded: Boolean = false,
    )
}
