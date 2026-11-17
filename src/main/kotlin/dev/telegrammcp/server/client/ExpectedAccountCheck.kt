package dev.telegrammcp.server.client

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.service.DurableFiles
import dev.telegrammcp.server.util.StructuredLogger
import it.tdlight.jni.TdApi
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * The Telegram account an operator expects a session to belong to.
 *
 * Both fields are optional; when both are set, both must match. Phone numbers
 * are compared by their digits only, so `+1 555 123-4567` matches TDLib's
 * `15551234567`.
 */
data class ExpectedAccount(val userId: Long? = null, val phoneNumber: String = "") {
    val phoneDigits: String = phoneNumber.filter(Char::isDigit)

    init {
        require(userId == null || userId > 0) { "expected-user-id must be a positive Telegram user ID" }
        require(phoneNumber.isBlank() || phoneDigits.length in 5..20) {
            "expected-phone-number must be a phone number in international format"
        }
    }

    val configured: Boolean get() = userId != null || phoneDigits.isNotEmpty()

    companion object {
        val NONE = ExpectedAccount()
    }
}

enum class AccountCheckResult {
    /** No expected account is configured; the session is served as before. */
    NOT_CONFIGURED,

    /** The signed-in account matches every configured expectation. */
    MATCH,

    /** The signed-in account is a different one; the session is refused. */
    MISMATCH,

    /** TDLib could not say who is signed in; the session is refused (fail closed). */
    UNVERIFIED,
}

/**
 * The last expected-account check for one account. Written next to the TDLib
 * database so the offline `session doctor` can show it, and removed together
 * with the session by `session clear`. Phone numbers are stored masked.
 */
data class AccountCheckRecord(
    val account: String,
    val result: AccountCheckResult,
    val checkedAt: Instant,
    val expectedUserId: Long? = null,
    val expectedPhone: String? = null,
    val actualUserId: Long? = null,
    val actualPhone: String? = null,
    val message: String,
) {
    val accepted: Boolean get() = result == AccountCheckResult.NOT_CONFIGURED || result == AccountCheckResult.MATCH
}

/**
 * Verifies, once TDLib reports `AuthorizationStateReady`, that the session is
 * the account the operator configured with `expected-user-id` and/or
 * `expected-phone-number`. A wrong session (an old copy of the database
 * directory, a mixed-up mount, a QR login from the wrong phone) is refused
 * before any tool call can reach it.
 */
class ExpectedAccountCheck(
    private val label: String,
    private val expected: ExpectedAccount,
    /** Where to persist the [AccountCheckRecord]; null skips persistence. */
    private val recordDirectory: Path?,
    /** How long the first GetMe may take. */
    private val timeout: Duration = Duration.ofSeconds(15),
    private val clock: Clock = Clock.systemUTC(),
    /**
     * Pauses before each retry when GetMe fails or times out; its size is the
     * number of retries. A wrong account is never retried, only a missing answer.
     */
    private val retryBackoff: List<Duration> = DEFAULT_RETRY_BACKOFF,
    /** How long each retried GetMe may take; shorter than [timeout] so retries fit the budget. */
    private val retryTimeout: Duration = Duration.ofSeconds(5),
    /**
     * Upper bound for the whole check, normally derived from the auth
     * ready-timeout with [budgetFor], so a waiting tool call gets the check's
     * verdict instead of an auth timeout. Every attempt is cut to the time left,
     * and no retry starts without at least [MIN_ATTEMPT] after its pause.
     * Null means no overall bound.
     */
    private val budget: Duration? = null,
) {
    private val log = StructuredLogger.forClass<ExpectedAccountCheck>()

    /**
     * Runs the check and calls exactly one of [onAccepted] or [onRefused].
     * Without an expectation [onAccepted] runs at once and [getMe] is never
     * called. Never blocks: [getMe] completes on a TDLib thread.
     */
    fun run(
        getMe: () -> CompletableFuture<TdApi.User>,
        onAccepted: (AccountCheckRecord) -> Unit,
        onRefused: (AccountCheckRecord) -> Unit,
    ) {
        if (!expected.configured) {
            val record = record(AccountCheckResult.NOT_CONFIGURED, null,
                "No expected account is configured for Telegram account '$label'")
            persist(record)
            onAccepted(record)
            return
        }
        val deadline = budget?.let { System.nanoTime() + it.toNanos() }
        attempt(getMe, onAccepted, onRefused, retry = 0, deadline = deadline)
    }

    private fun remainingMillis(deadline: Long?): Long? =
        deadline?.let { TimeUnit.NANOSECONDS.toMillis(it - System.nanoTime()) }

    private fun attempt(
        getMe: () -> CompletableFuture<TdApi.User>,
        onAccepted: (AccountCheckRecord) -> Unit,
        onRefused: (AccountCheckRecord) -> Unit,
        retry: Int,
        deadline: Long?,
    ) {
        val limit = (if (retry == 0) timeout else retryTimeout).toMillis()
        val attemptMillis = remainingMillis(deadline)?.let { minOf(limit, it) }?.coerceAtLeast(1) ?: limit
        val future = try {
            getMe()
        } catch (error: Exception) {
            CompletableFuture.failedFuture(error)
        }
        future.orTimeout(attemptMillis, TimeUnit.MILLISECONDS).whenComplete { user, error ->
            val pause = retryBackoff.getOrNull(retry)
            val left = remainingMillis(deadline)
            val retryFits = pause != null && (left == null || left >= pause.toMillis() + MIN_ATTEMPT.toMillis())
            if ((error != null || user == null) && pause != null && retryFits) {
                log.warn("Could not verify Telegram account '{}' (attempt {} of {}); retrying in {} ms",
                    label, retry + 1, retryBackoff.size + 1, pause.toMillis())
                CompletableFuture.runAsync(
                    { attempt(getMe, onAccepted, onRefused, retry + 1, deadline) },
                    CompletableFuture.delayedExecutor(pause.toMillis(), TimeUnit.MILLISECONDS),
                )
                return@whenComplete
            }
            val record = if (error != null || user == null) {
                unverified(error, attempts = retry + 1)
            } else {
                evaluate(user)
            }
            persist(record)
            if (record.accepted) {
                log.info("Telegram account '{}' matches the expected account", label)
                onAccepted(record)
            } else {
                log.error(record.message)
                onRefused(record)
            }
        }
    }

    /** Compares [user] with the expectation. Pure; exposed for tests. */
    fun evaluate(user: TdApi.User): AccountCheckRecord {
        val actualDigits = user.phoneNumber.orEmpty().filter(Char::isDigit)
        val problems = buildList {
            if (expected.userId != null && expected.userId != user.id) {
                add("user ID ${user.id}, not the expected ${expected.userId}")
            }
            if (expected.phoneDigits.isNotEmpty() && expected.phoneDigits != actualDigits) {
                add("phone ${maskPhone(actualDigits)}, not the expected ${maskPhone(expected.phoneDigits)}")
            }
        }
        if (problems.isEmpty()) {
            return record(AccountCheckResult.MATCH, user, "Telegram account '$label' is the expected account")
        }
        return record(
            AccountCheckResult.MISMATCH,
            user,
            "Telegram account '$label' is signed in with ${problems.joinToString(" and ")}. " +
                "Refusing to serve this session. Sign in with the expected account (stop the server, " +
                "'session clear --account $label --yes', then log in again), or correct expected-user-id / " +
                "expected-phone-number for this account.",
        )
    }

    private fun unverified(error: Throwable?, attempts: Int): AccountCheckRecord = record(
        AccountCheckResult.UNVERIFIED,
        null,
        "Could not verify that Telegram account '$label' is the expected account after $attempts attempt" +
            (if (attempts == 1) "" else "s") +
            " (${(error?.cause ?: error)?.javaClass?.simpleName ?: "no answer"}). Refusing to serve this session; " +
            "restart the server to retry the check.",
    )

    private fun record(result: AccountCheckResult, user: TdApi.User?, message: String) = AccountCheckRecord(
        account = label,
        result = result,
        checkedAt = clock.instant(),
        expectedUserId = expected.userId,
        expectedPhone = expected.phoneDigits.takeIf(String::isNotEmpty)?.let(::maskPhone),
        actualUserId = user?.id,
        actualPhone = user?.phoneNumber?.filter(Char::isDigit)?.takeIf(String::isNotEmpty)?.let(::maskPhone),
        message = message,
    )

    private fun persist(record: AccountCheckRecord) {
        val directory = recordDirectory ?: return
        runCatching { write(directory, record) }
            .onFailure { log.warn("Could not record the account check for '{}': {}", label, it.message) }
    }

    companion object {
        const val RECORD_FILE = "telegram-mcp-account-check.json"

        /** Three retries over about 7 seconds, on top of each attempt's own timeout. */
        val DEFAULT_RETRY_BACKOFF: List<Duration> = listOf(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4))

        /** The shortest attempt worth starting; a retry that would get less is skipped. */
        val MIN_ATTEMPT: Duration = Duration.ofSeconds(1)

        /**
         * The check's budget for an auth [readyTimeout]: the ready-timeout minus a
         * margin (a fifth, at most 5 seconds) so the verdict lands before a
         * waiting tool call gives up. The 45s default gives 40s, which covers the
         * worst case of the defaults: 15 + 1 + 5 + 2 + 5 + 4 + 5 = 37 seconds.
         */
        fun budgetFor(readyTimeout: Duration): Duration {
            val margin = minOf(Duration.ofSeconds(5), readyTimeout.dividedBy(5))
            return readyTimeout.minus(margin)
        }

        private val mapper: ObjectMapper = jacksonObjectMapper().findAndRegisterModules()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

        /** Keeps the country code's first digit and the last two digits: `+1********67`. */
        fun maskPhone(digits: String): String {
            val clean = digits.filter(Char::isDigit)
            if (clean.length <= 3) return "+" + "*".repeat(clean.length)
            return "+" + clean.first() + "*".repeat(clean.length - 3) + clean.takeLast(2)
        }

        fun write(directory: Path, record: AccountCheckRecord) {
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return
            DurableFiles.write(directory.resolve(RECORD_FILE), mapper.writeValueAsBytes(record))
        }

        /** Reads the last record, or null when no check ran or the file is unreadable. */
        fun read(directory: Path): AccountCheckRecord? {
            val file = directory.resolve(RECORD_FILE)
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 64 * 1024) return null
            return runCatching { mapper.readValue(Files.readAllBytes(file), AccountCheckRecord::class.java) }.getOrNull()
        }
    }
}
