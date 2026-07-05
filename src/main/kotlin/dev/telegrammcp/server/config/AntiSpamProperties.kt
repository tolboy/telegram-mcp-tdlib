package dev.telegrammcp.server.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration for the anti-spam guard.
 *
 * The guard is deliberately strict for write operations targeting **external**
 * chats (anything not in [internalChatIds]) so a misbehaving caller cannot
 * trigger Telegram-side anti-spam (which would lock the user account).
 *
 * Limits are enforced on three axes:
 *
 * 1. **Sliding window** (`maxOps` per `windowMs`) — the standard rate limit.
 *    Optionally a stricter `maxOpsExternal` / `windowMsExternal` applies when
 *    the operation targets a chat outside [internalChatIds].
 * 2. **Daily cap** (`dailyMax`) — long-horizon counter, resets at midnight UTC.
 * 3. **Duplicate detection** (`dedupTextField` + `dedupWindowMs`) — same
 *    `(target chat, text)` within window is rejected as a duplicate.
 *
 * Built-in defaults live in [dev.telegrammcp.server.service.AntiSpamGuardService].
 * Anything in [rules] overrides those defaults; anything not configured falls
 * back to [defaultRule].
 */
@ConfigurationProperties(prefix = "anti-spam")
data class AntiSpamProperties(
    /** Master switch. */
    val enabled: Boolean = true,

    /** Chat IDs treated as the privileged admin context (looser limits). */
    val internalChatIds: List<Long> = emptyList(),

    /** Rule used for any tool that has neither a built-in nor configured rule. */
    val defaultRule: RuleProps = RuleProps(maxOps = 60, windowMs = 60_000L),

    /** Per-tool overrides. Tool name → rule. */
    val rules: Map<String, RuleProps> = emptyMap(),

    /** Admin notifier (digest of throttled events). */
    val notifier: NotifierProps = NotifierProps(),

    /**
     * Hard ceilings per tool — runtime overrides written via the admin policy
     * commands cannot exceed these. Floors (e.g. `windowMsFloor`) work in
     * the opposite direction: they cap how *loose* a runtime override may make
     * a rule. Empty map = no enforcement (current behavior).
     */
    val ceilings: Map<String, RuleCeilings> = emptyMap(),

    /**
     * Path to the local JSON file used by [dev.telegrammcp.server.service.AntiSpamPolicyService]
     * to persist runtime per-tool overrides. Relative paths resolve below the
     * platform application-data directory; `null` or blank disables persistence.
     */
    val overridesFile: String? = "anti-spam-overrides.json",
) {
    data class RuleProps(
        /** Max operations within [windowMs] (per `keyField` if set, else global). */
        val maxOps: Int = 60,

        /** Window duration for the sliding limit. */
        val windowMs: Long = 60_000L,

        /** Stricter `maxOps` when the target is external. `null` = use [maxOps]. */
        val maxOpsExternal: Int? = null,

        /** Stricter window when the target is external. `null` = use [windowMs]. */
        val windowMsExternal: Long? = null,

        /**
         * Argument name whose value scopes the sliding window
         * (e.g. `"chat_id"` so each chat has its own window). `null` = global window.
         */
        val keyField: String? = null,

        /** Argument name whose value participates in duplicate detection. */
        val dedupTextField: String? = null,

        /** Window for duplicate detection. */
        val dedupWindowMs: Long = 30_000L,

        /** Max operations per day (per tool). `-1` = unlimited. */
        val dailyMax: Int = -1,

        /** When true, the tool bypasses anti-spam entirely (read-only ops). */
        val exempt: Boolean = false,
    )

    /**
     * Per-tool ceiling/floor envelope for runtime overrides. `null` fields mean
     * "no ceiling for this dimension" — the override may use any value the
     * built-in rule or yaml override already permits.
     */
    data class RuleCeilings(
        /** Max value a runtime override may set for `maxOps` / `maxOpsExternal`. */
        val maxOpsCeiling: Int? = null,
        /** Max value a runtime override may set for `dailyMax`. */
        val dailyMaxCeiling: Int? = null,
        /** Min value a runtime override may set for `windowMs` / `windowMsExternal`. */
        val windowMsFloor: Long? = null,
        /** Min value a runtime override may set for `dedupWindowMs`. */
        val dedupWindowMsFloor: Long? = null,
    )

    data class NotifierProps(
        /** Whether the digest notifier is active. */
        val enabled: Boolean = true,

        /** How often the digest job runs. */
        val intervalSeconds: Long = 60L,

        /** Cap how many events one digest message lists. */
        val maxEventsPerNotification: Int = 20,
    )
}
