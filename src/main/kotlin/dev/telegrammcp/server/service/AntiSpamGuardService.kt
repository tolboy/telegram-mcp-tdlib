package dev.telegrammcp.server.service

import dev.telegrammcp.server.config.AntiSpamProperties
import dev.telegrammcp.server.config.AntiSpamProperties.RuleProps
import dev.telegrammcp.server.client.TelegramAccountContext
import dev.telegrammcp.server.exception.AntiSpamException
import dev.telegrammcp.server.util.StructuredLogger
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * Global anti-spam / rate-limiting guard for any tool that mutates the
 * connected Telegram account.
 *
 * Goals:
 *  - Prevent an MCP host (or any caller) from triggering Telegram's own
 *    anti-spam (FLOOD_WAIT, account locks) by capping how often we do
 *    "human-ish" actions.
 *  - Detect duplicate broadcasts (same text to same chat) within a short
 *    window — a common abuse pattern.
 *  - Apply tighter limits to **external** chats (anything outside
 *    [AntiSpamProperties.internalChatIds], i.e. not an explicitly trusted internal chat).
 *
 * The guard is invoked from [OperationGuardService.checkPermission] so every
 * tool that already calls the operation guard inherits anti-spam protection
 * for free — no per-tool wiring required.
 *
 * Throttled events accumulate in an in-memory queue that
 * [AntiSpamNotifier] drains and digests into a single message back to the
 * admin chat.
 */
@Service
class AntiSpamGuardService(
    private val props: AntiSpamProperties,
    private val meterRegistry: MeterRegistry,
    private val policy: AntiSpamPolicyService,
    private val accountContext: TelegramAccountContext? = null,
) {

    private val log = StructuredLogger.forClass<AntiSpamGuardService>()

    /** Built-in rules — overridable from configuration via [AntiSpamProperties.rules]. */
    private val builtInRules: Map<String, RuleProps> = mapOf(
        // ─── Heavy chat-shape mutations: very tight ────────────────────────
        "create_channel" to RuleProps(
            maxOps = 1, windowMs = 60_000L,
            maxOpsExternal = 1, windowMsExternal = 60_000L,
            dailyMax = 5,
        ),
        "create_supergroup" to RuleProps(
            maxOps = 1, windowMs = 60_000L,
            dailyMax = 5,
        ),
        "create_group" to RuleProps(
            maxOps = 1, windowMs = 60_000L,
            dailyMax = 5,
        ),
        "create_topic" to RuleProps(
            maxOps = 5, windowMs = 60_000L,
            keyField = "chat_id",
            dailyMax = 50,
        ),
        "set_forum_topics_enabled" to RuleProps(
            maxOps = 5, windowMs = 60_000L,
            keyField = "chat_id",
        ),

        // ─── Photo / title changes: per-chat throttle ──────────────────────
        "edit_chat_photo" to RuleProps(maxOps = 2, windowMs = 60_000L, keyField = "chat_id"),
        "delete_chat_photo" to RuleProps(maxOps = 2, windowMs = 60_000L, keyField = "chat_id"),
        "edit_chat_title" to RuleProps(maxOps = 3, windowMs = 60_000L, keyField = "chat_id"),
        "set_profile_photo" to RuleProps(maxOps = 2, windowMs = 60_000L, dailyMax = 10),
        "delete_profile_photo" to RuleProps(maxOps = 2, windowMs = 60_000L, dailyMax = 10),
        "update_profile" to RuleProps(maxOps = 2, windowMs = 60_000L, dailyMax = 10),

        // ─── Joining / subscribing: classic spam vector ────────────────────
        "join_chat_by_link" to RuleProps(maxOps = 5, windowMs = 60 * 60_000L, dailyMax = 30),
        "subscribe_public_channel" to RuleProps(maxOps = 5, windowMs = 60 * 60_000L, dailyMax = 30),
        "leave_chat" to RuleProps(maxOps = 5, windowMs = 60_000L, dailyMax = 50),
        "invite_to_group" to RuleProps(maxOps = 5, windowMs = 60_000L, keyField = "chat_id", dailyMax = 50),

        // ─── Messaging: per-chat with duplicate detection ──────────────────
        "send_message" to RuleProps(
            maxOps = 30, windowMs = 60_000L,
            maxOpsExternal = 6, windowMsExternal = 60_000L,
            keyField = "chat_id",
            dedupTextField = "text", dedupWindowMs = 60_000L,
        ),
        "reply_to_message" to RuleProps(
            maxOps = 30, windowMs = 60_000L,
            maxOpsExternal = 6, windowMsExternal = 60_000L,
            keyField = "chat_id",
            dedupTextField = "text", dedupWindowMs = 60_000L,
        ),
        "edit_message" to RuleProps(
            maxOps = 20, windowMs = 60_000L,
            keyField = "chat_id",
        ),
        "forward_message" to RuleProps(
            maxOps = 20, windowMs = 60_000L,
            maxOpsExternal = 5, windowMsExternal = 60_000L,
            keyField = "to_chat_id",
        ),
        "send_file" to RuleProps(
            maxOps = 10, windowMs = 60_000L,
            maxOpsExternal = 3, windowMsExternal = 60_000L,
            keyField = "chat_id",
        ),
        "send_voice" to RuleProps(
            maxOps = 10, windowMs = 60_000L,
            maxOpsExternal = 3, windowMsExternal = 60_000L,
            keyField = "chat_id",
        ),
        "transcribe_voice_note" to RuleProps(
            maxOps = 6, windowMs = 60_000L,
            keyField = "chat_id",
            dailyMax = 60,
        ),
        "send_sticker" to RuleProps(
            maxOps = 15, windowMs = 60_000L,
            maxOpsExternal = 5, windowMsExternal = 60_000L,
            keyField = "chat_id",
        ),
        "send_reaction" to RuleProps(maxOps = 30, windowMs = 60_000L, keyField = "chat_id"),
        "remove_reaction" to RuleProps(maxOps = 30, windowMs = 60_000L, keyField = "chat_id"),
        "create_poll" to RuleProps(maxOps = 5, windowMs = 60_000L, keyField = "chat_id"),
        "delete_message" to RuleProps(maxOps = 30, windowMs = 60_000L, keyField = "chat_id"),
        "pin_message" to RuleProps(maxOps = 5, windowMs = 60_000L, keyField = "chat_id"),
        "unpin_message" to RuleProps(maxOps = 5, windowMs = 60_000L, keyField = "chat_id"),

        // ─── Contact / user management ─────────────────────────────────────
        "add_contact" to RuleProps(maxOps = 5, windowMs = 60_000L, dailyMax = 50),
        "delete_contact" to RuleProps(maxOps = 10, windowMs = 60_000L, dailyMax = 100),
        "block_user" to RuleProps(maxOps = 5, windowMs = 60_000L, dailyMax = 30),
        "unblock_user" to RuleProps(maxOps = 5, windowMs = 60_000L, dailyMax = 30),
        "ban_user" to RuleProps(maxOps = 5, windowMs = 60_000L, keyField = "chat_id", dailyMax = 50),
        "unban_user" to RuleProps(maxOps = 5, windowMs = 60_000L, keyField = "chat_id", dailyMax = 50),
        "promote_admin" to RuleProps(maxOps = 5, windowMs = 60_000L, keyField = "chat_id", dailyMax = 50),
        "demote_admin" to RuleProps(maxOps = 5, windowMs = 60_000L, keyField = "chat_id", dailyMax = 50),

        // ─── Cheap state toggles: loose ────────────────────────────────────
        "mark_as_read" to RuleProps(maxOps = 60, windowMs = 60_000L),
        "archive_chat" to RuleProps(maxOps = 30, windowMs = 60_000L),
        "unarchive_chat" to RuleProps(maxOps = 30, windowMs = 60_000L),
        "mute_chat" to RuleProps(maxOps = 30, windowMs = 60_000L),
        "unmute_chat" to RuleProps(maxOps = 30, windowMs = 60_000L),
        "save_draft" to RuleProps(maxOps = 60, windowMs = 60_000L),
        "clear_draft" to RuleProps(maxOps = 60, windowMs = 60_000L),
        "press_inline_button" to RuleProps(maxOps = 30, windowMs = 60_000L),
        "get_invite_link" to RuleProps(maxOps = 10, windowMs = 60_000L, keyField = "chat_id"),

        // ─── Read tools: exempt — they don't trip Telegram's anti-spam ─────
        "get_me" to RuleProps(exempt = true),
        "get_chat" to RuleProps(exempt = true),
        "list_chats" to RuleProps(exempt = true),
        "get_history" to RuleProps(exempt = true),
        "get_messages" to RuleProps(exempt = true),
        "search_messages" to RuleProps(exempt = true),
        "search_global" to RuleProps(exempt = true),
        "search_public_chats" to RuleProps(exempt = true),
        "get_message_context" to RuleProps(exempt = true),
        "list_topics" to RuleProps(exempt = true),
        "list_contacts" to RuleProps(exempt = true),
        "list_drafts" to RuleProps(exempt = true),
        "get_drafts" to RuleProps(exempt = true),
        "list_inline_buttons" to RuleProps(exempt = true),
        "resolve_username" to RuleProps(exempt = true),
        "search_contacts" to RuleProps(exempt = true),
        "get_user_status" to RuleProps(exempt = true),
        "get_last_interaction" to RuleProps(exempt = true),
        "get_participants" to RuleProps(exempt = true),
        "get_pinned_messages" to RuleProps(exempt = true),
        "get_message_reactions" to RuleProps(exempt = true),
        "get_admins" to RuleProps(exempt = true),
        "get_banned_users" to RuleProps(exempt = true),
        "get_blocked_users" to RuleProps(exempt = true),
        "get_user_photos" to RuleProps(exempt = true),
        "get_recent_actions" to RuleProps(exempt = true),
        "get_sticker_sets" to RuleProps(exempt = true),
        "get_media_info" to RuleProps(exempt = true),
        "download_media" to RuleProps(exempt = true),
        "message_from_link" to RuleProps(exempt = true),
        "register_internal_chat" to RuleProps(exempt = true),

        // ─── Public-search read-only tools — exempt ───────────────────────
        "discover_public_chats" to RuleProps(exempt = true),
        "search_public_messages" to RuleProps(exempt = true),
    )

    /** (toolName + scope + key) → sliding window of timestamps. */
    private val windows = ConcurrentHashMap<String, ArrayDeque<Long>>()

    /** (toolName + isoDate) → ops that day. */
    private val dailyCounters = ConcurrentHashMap<String, AtomicLong>()

    /** (toolName + key + textHash) → last-seen timestamp for duplicate detection. */
    private val dedupCache = ConcurrentHashMap<String, Long>()

    /** Internal chats are account-scoped; a numeric Telegram chat ID may recur across sessions. */
    private val internalChatIdsByAccount = ConcurrentHashMap<String, MutableSet<Long>>().apply {
        put("default", ConcurrentHashMap.newKeySet<Long>().also { it.addAll(props.internalChatIds) })
    }

    /** Pending throttle events are drained only by the account that produced them. */
    private val pendingEventsByAccount = ConcurrentHashMap<String, ConcurrentLinkedDeque<ThrottleEvent>>()

    /** Bypass flag (per-thread) — set by trusted callers (e.g. AntiSpamNotifier). */
    private val bypass: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }

    /** Adds a chat ID to the internal allow-list at runtime. */
    fun registerInternalChat(chatId: Long, account: String = currentAccountLabel()) {
        internalChatIdsByAccount.computeIfAbsent(account) { ConcurrentHashMap.newKeySet() }.add(chatId)
        log.info("Anti-spam: chat {} registered as internal admin context for account '{}'", chatId, account)
    }

    /** Whether a chat ID is currently in the internal allow-list. */
    fun isInternalChat(chatId: Long, account: String = currentAccountLabel()): Boolean =
        chatId in internalChatIdsByAccount[account].orEmpty()

    fun internalChatIds(account: String = currentAccountLabel()): Set<Long> =
        internalChatIdsByAccount[account].orEmpty().toSet()

    /**
     * Runs [block] with anti-spam disabled for the current thread.
     * Used by the digest notifier to avoid recursive throttling of its own
     * admin-notification messages.
     */
    fun <T> withBypass(block: () -> T): T {
        val prior = bypass.get()
        bypass.set(true)
        return try {
            block()
        } finally {
            bypass.set(prior)
        }
    }

    /**
     * Pulls the next batch of throttle events for digest notification.
     * Removed events are not retained.
     */
    fun popPendingEvents(account: String, maxEvents: Int): List<ThrottleEvent> {
        val pendingEvents = pendingEventsByAccount[account] ?: return emptyList()
        val out = ArrayList<ThrottleEvent>(maxEvents.coerceAtLeast(0))
        var i = 0
        while (i < maxEvents) {
            val event = pendingEvents.pollFirst() ?: break
            out.add(event)
            i++
        }
        return out
    }

    /** Backwards-compatible default-account view for single-account callers. */
    fun popPendingEvents(maxEvents: Int): List<ThrottleEvent> = popPendingEvents("default", maxEvents)

    /**
     * Verifies that [toolName] with [arguments] is allowed by anti-spam policy.
     * Records the operation if allowed; throws [AntiSpamException] if not.
     */
    fun check(toolName: String, arguments: Map<String, Any>) {
        if (!props.enabled || bypass.get()) return

        val rule = effectiveRule(toolName)
        if (rule.exempt) return

        val now = System.currentTimeMillis()
        val keyValue = extractKey(arguments, rule.keyField)
        val account = currentAccountLabel()
        val targetChatId = extractChatId(arguments)
        val external = !isInternalTarget(account, targetChatId)

        // 1. Daily cap (isolated per account and tool).
        if (rule.dailyMax > 0) {
            val today = LocalDate.now(ZoneOffset.UTC).toString()
            val dayKey = "$account|$toolName|$today"
            val counter = dailyCounters.computeIfAbsent(dayKey) { AtomicLong(0) }
            if (counter.get() >= rule.dailyMax) {
                rejectAndRecord(account, toolName, arguments, "daily limit ${rule.dailyMax}/day reached", null, external)
            }
        }

        // 2. Sliding window (per key + scope).
        val maxOps = if (external) rule.maxOpsExternal ?: rule.maxOps else rule.maxOps
        val windowMs = if (external) rule.windowMsExternal ?: rule.windowMs else rule.windowMs
        val scope = if (external) "ext" else "int"
        val windowKey = "$account|$toolName|$scope|${keyValue ?: "*"}"
        val deque = windows.computeIfAbsent(windowKey) { ArrayDeque() }
        synchronized(deque) {
            while (true) {
                val first = deque.peekFirst() ?: break
                if (now - first > windowMs) deque.pollFirst() else break
            }
            if (deque.size >= maxOps) {
                val oldest = deque.peekFirst() ?: now
                val retryAfterMs = (windowMs - (now - oldest)).coerceAtLeast(0)
                rejectAndRecord(
                    account,
                    toolName,
                    arguments,
                    "rate limit $maxOps ops per ${windowMs / 1000}s" +
                        if (external) " (external)" else "",
                    retryAfterMs,
                    external,
                )
            }
        }

        // 3. Duplicate detection (per key + dedup text).
        if (rule.dedupTextField != null) {
            val text = arguments[rule.dedupTextField]?.toString()
            if (!text.isNullOrEmpty()) {
                val hash = "$account|$toolName|${keyValue ?: "*"}|${text.hashCode()}"
                val previous = dedupCache[hash]
                if (previous != null && now - previous < rule.dedupWindowMs) {
                    val retryAfterMs = (rule.dedupWindowMs - (now - previous)).coerceAtLeast(0)
                    rejectAndRecord(
                        account,
                        toolName,
                        arguments,
                        "duplicate text within ${rule.dedupWindowMs / 1000}s",
                        retryAfterMs,
                        external,
                    )
                }
                dedupCache[hash] = now
                pruneDedup(now)
            }
        }

        // 4. Commit: append to window, increment daily counter.
        synchronized(deque) { deque.addLast(now) }
        if (rule.dailyMax > 0) {
            val today = LocalDate.now(ZoneOffset.UTC).toString()
            val dayKey = "$account|$toolName|$today"
            dailyCounters.computeIfAbsent(dayKey) { AtomicLong(0) }.incrementAndGet()
        }

        meterRegistry.counter(
            "mcp.antispam.allowed",
            "tool", toolName,
            "scope", scope,
        ).increment()
    }

    private fun rejectAndRecord(
        account: String,
        toolName: String,
        arguments: Map<String, Any>,
        reason: String,
        retryAfterMs: Long?,
        external: Boolean,
    ): Nothing {
        val targetChatId = extractChatId(arguments)
        val event = ThrottleEvent(
            timestamp = Instant.now(),
            account = account,
            toolName = toolName,
            reason = reason,
            chatId = targetChatId,
            external = external,
            retryAfterMs = retryAfterMs,
        )
        val pendingEvents = pendingEventsByAccount.computeIfAbsent(account) { ConcurrentLinkedDeque() }
        pendingEvents.addLast(event)
        // Cap queue so it can't grow unbounded if the notifier is down.
        while (pendingEvents.size > 500) {
            pendingEvents.pollFirst()
        }

        meterRegistry.counter(
            "mcp.antispam.blocked",
            "tool", toolName,
            "scope", if (external) "ext" else "int",
        ).increment()

        log.warn(
            "Anti-spam blocked tool='{}' chat={} external={} reason={}",
            toolName, targetChatId, external, reason,
        )
        throw AntiSpamException(toolName, reason, retryAfterMs)
    }

    private fun effectiveRule(toolName: String): RuleProps {
        val base = builtInRules[toolName] ?: props.defaultRule
        return policy.effectiveRule(toolName, base)
    }

    private fun extractKey(arguments: Map<String, Any>, field: String?): String? {
        if (field == null) return null
        return arguments[field]?.toString()
    }

    private fun extractChatId(arguments: Map<String, Any>): Long? {
        // Most tools use "chat_id"; forward_message uses "to_chat_id".
        val raw = arguments["chat_id"] ?: arguments["to_chat_id"] ?: return null
        return when (raw) {
            is Number -> raw.toLong()
            is String -> raw.trim().toLongOrNull()
            else -> null
        }
    }

    private fun isInternalTarget(account: String, chatId: Long?): Boolean {
        if (chatId == null) return false
        return chatId in internalChatIdsByAccount[account].orEmpty()
    }

    private fun currentAccountLabel(): String =
        runCatching { accountContext?.currentAccount() }.getOrNull() ?: "default"

    private fun pruneDedup(now: Long) {
        // Drop dedup entries older than 1 hour to keep the map bounded.
        if (dedupCache.size < 1000) return
        val threshold = now - 60 * 60_000L
        dedupCache.entries.removeIf { it.value < threshold }
    }

    /**
     * A single anti-spam rejection — buffered for digest notification.
     */
    data class ThrottleEvent(
        val timestamp: Instant,
        val account: String,
        val toolName: String,
        val reason: String,
        val chatId: Long?,
        val external: Boolean,
        val retryAfterMs: Long?,
    )
}
