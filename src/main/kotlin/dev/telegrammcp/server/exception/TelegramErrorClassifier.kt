package dev.telegrammcp.server.exception

/**
 * What a TDLib `error` object means for the caller and for the circuit breaker.
 *
 * TDLib error codes are HTTP-like but not reliable on their own: TDLib returns
 * many fixed, request-specific outcomes as 500 ("Topic not found", "Unknown
 * parse mode") and many permission failures as 400 ("Can't access the chat").
 * [TelegramErrorClassifier] therefore combines the code with explicit tables of
 * error strings taken from the TDLib sources and from Telegram's server error
 * names that TDLib passes through verbatim.
 */
enum class TelegramErrorKind(
    /** True when the failure reflects Telegram/TDLib health and should count toward the circuit breaker. */
    val countsTowardBreaker: Boolean,
) {
    /** Flood control. TDLib already waited out 420 / SLOWMODE internally and gave up; the request was not executed. */
    FLOOD_WAIT(false),
    AUTH_REQUIRED(false),
    PERMISSION_DENIED(false),
    NOT_FOUND(false),
    INVALID_INPUT(false),

    /** The request was cancelled or aborted locally (for example during shutdown); its outcome is unknown. */
    CANCELLED(false),

    /** Allow-listed transient server failure; an unchanged read may be retried. */
    TRANSIENT_SERVER(true),

    /** Any other server-side failure. The request may or may not have been executed. */
    SERVER_ERROR(true),

    /** A code outside the documented ranges. */
    UNKNOWN(false),
}

object TelegramErrorClassifier {

    /**
     * Wait-type errors TDLib's NetQueryDelayer understands (td/telegram/net/NetQueryDelayer.cpp).
     * They normally never reach the client: TDLib sleeps and resends, then returns
     * `429 Too Many Requests: retry after N` once the total wait exceeds its limit.
     */
    private val WAIT_SUFFIX = Regex("""\b(?:FLOOD_WAIT|FLOOD_PREMIUM_WAIT|SLOWMODE_WAIT|2FA_CONFIRM_WAIT|TAKEOUT_INIT_DELAY)_(\d+)\b""")
    private val RETRY_AFTER = Regex("""\bretry after (\d+)\b""", RegexOption.IGNORE_CASE)

    /** Permission failures TDLib reports as 400 (TDLib-local strings). */
    private val PERMISSION_PREFIXES = listOf(
        "Can't access the chat",
        "Can't access the user",
        "Can't access the topic",
        "Can't access sender chat",
        "Can't access the chosen chat",
        "Not enough rights",
        "Have not enough rights",
        "Have no access to the chat",
        "Have no write access to the chat",
        "Have no access to the user",
        "Chat is not accessible",
        "User is inaccessible",
    )

    /** Telegram server error names (core.telegram.org/api/errors) that TDLib forwards as 400. */
    private val PERMISSION_NAMES = setOf(
        "CHAT_ADMIN_REQUIRED",
        "RIGHT_FORBIDDEN",
        "CHAT_WRITE_FORBIDDEN",
        "CHAT_RESTRICTED",
        "CHAT_FORBIDDEN",
        "CHANNEL_PRIVATE",
        "USER_PRIVACY_RESTRICTED",
        "USER_BANNED_IN_CHANNEL",
        "USER_NOT_MUTUAL_CONTACT",
        "USER_IS_BLOCKED",
        "YOU_BLOCKED_USER",
        "MESSAGE_AUTHOR_REQUIRED",
    )
    private val PERMISSION_NAME_PATTERN = Regex("""^CHAT_SEND_[A-Z_]+_FORBIDDEN$""")

    /**
     * "Object does not exist" outcomes, matched exactly. Local lookups that are
     * not Telegram objects ("File not found", "Chat list not found") are
     * deliberately absent and stay INVALID_INPUT.
     */
    private val NOT_FOUND_MESSAGES = setOf(
        // TDLib-local strings (td/telegram/*.cpp)
        "Chat not found",
        "Message not found",
        "User not found",
        "Supergroup not found",
        "Basic group not found",
        "Topic not found",
        "Story not found",
        "Sticker set not found",
        "Created sticker set not found",
        "Invalid chat identifier specified",
        "Wrong chat identifier specified",
        "Invalid user identifier specified",
        "Invalid user identifier",
        "Invalid message identifier specified",
        "Invalid message identifier",
        "Wrong message identifier",
        "Wrong message identifier specified",
        "Wrong scheduled message identifier",
        "Invalid message thread identifier specified",
        "Invalid forum topic identifier specified",
        "Invalid topic identifier specified",
        "Invalid story identifier specified",
        "Wrong story identifier specified",
        "Can't find secret chat",
        "Can't find message to copy",
        "Chat to forward messages to not found",
        "Chat to forward messages from not found",
        "Chat to copy message from not found",
        // Telegram server error names forwarded by TDLib
        "MSG_ID_INVALID",
        "MESSAGE_ID_INVALID",
        "CHANNEL_INVALID",
        "CHAT_ID_INVALID",
        "PEER_ID_INVALID",
        "USER_ID_INVALID",
        "USERNAME_NOT_OCCUPIED",
        "STICKERSET_INVALID",
        "TOPIC_ID_INVALID",
    )

    /** Fixed, request-specific outcomes TDLib reports as 500; retrying cannot change them. */
    private val FIXED_500: Map<String, TelegramErrorKind> = mapOf(
        "Chat not found" to TelegramErrorKind.NOT_FOUND,
        "Topic not found" to TelegramErrorKind.NOT_FOUND,
        "Sticker set not found" to TelegramErrorKind.NOT_FOUND,
        "Created sticker set not found" to TelegramErrorKind.NOT_FOUND,
        "Unknown parse mode" to TelegramErrorKind.INVALID_INPUT,
        "Can't find suitable file name" to TelegramErrorKind.INVALID_INPUT,
    )

    /** Cancellation strings: -1/1/200 "Canceled…", 406 "Request canceled", 500 "Request aborted". */
    private fun isCancellation(message: String): Boolean =
        message.startsWith("Canceled") || message == "Request canceled" || message == "Request aborted"

    /** Server failures known to be transient. Everything else in 5xx may be a fixed outcome. */
    private fun isTransientServer(code: Int, message: String): Boolean =
        code == 502 || code == 503 || message.startsWith("WORKER_BUSY") || message == "Timeout expired"

    /**
     * Telegram's PEER_FLOOD: the account is spam-restricted and may not message
     * users who have not contacted it. Despite the name this is not a timed flood
     * wait (no delay, 400), so it is a permission failure and never retryable.
     */
    private const val PEER_FLOOD = "PEER_FLOOD"

    fun isSpamRestricted(message: String): Boolean = message == PEER_FLOOD

    /**
     * The delay a flood error asks for. "retry after 0" (or a 0 suffix) is
     * reported as 1 second so callers never see retry_after_seconds: 0 next to
     * a flood the local gate holds for at least 1 second.
     */
    fun parseRetryAfterSeconds(message: String): Long? =
        (WAIT_SUFFIX.find(message) ?: RETRY_AFTER.find(message))?.groupValues?.get(1)?.toLongOrNull()
            ?.coerceAtLeast(1)

    fun isFloodWait(code: Int, message: String): Boolean =
        code == 420 || code == 429 || WAIT_SUFFIX.containsMatchIn(message)

    fun isPermissionDenied(message: String): Boolean =
        PERMISSION_PREFIXES.any { message.startsWith(it) } ||
            message in PERMISSION_NAMES || PERMISSION_NAME_PATTERN.matches(message)

    fun isNotFound(message: String): Boolean = message in NOT_FOUND_MESSAGES

    fun classify(code: Int, message: String): TelegramErrorKind = when {
        isCancellation(message) -> TelegramErrorKind.CANCELLED
        isSpamRestricted(message) -> TelegramErrorKind.PERMISSION_DENIED
        isFloodWait(code, message) -> TelegramErrorKind.FLOOD_WAIT
        code == 401 -> TelegramErrorKind.AUTH_REQUIRED
        code == 403 -> TelegramErrorKind.PERMISSION_DENIED
        code == 404 -> TelegramErrorKind.NOT_FOUND
        code in 400..499 && isPermissionDenied(message) -> TelegramErrorKind.PERMISSION_DENIED
        code in 400..499 && isNotFound(message) -> TelegramErrorKind.NOT_FOUND
        code in 400..499 -> TelegramErrorKind.INVALID_INPUT
        code in 500..599 && message in FIXED_500 -> FIXED_500.getValue(message)
        code in 500..599 && isTransientServer(code, message) -> TelegramErrorKind.TRANSIENT_SERVER
        code in 500..599 -> TelegramErrorKind.SERVER_ERROR
        else -> TelegramErrorKind.UNKNOWN
    }
}
