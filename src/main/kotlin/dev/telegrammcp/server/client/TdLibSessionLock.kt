package dev.telegrammcp.server.client

import it.tdlight.client.TelegramError
import java.nio.file.Path
import java.util.Locale

/**
 * Recognises the one TDLib startup failure that no amount of waiting can fix:
 * another live process already owns the session database.
 *
 * TDLib reports it as an error on `SetTdlibParameters`, which tdlight routes to
 * the default exception handler — never through an authorization state — so
 * without an explicit check it only shows up as `WARN Unhandled exception!`
 * while the server waits for an authentication that can never arrive.
 */
internal object TdLibSessionLock {

    /*
     * tdlight preserves TDLib errors as TelegramError, including the numeric
     * code and the unprefixed TDLib message. TDLib does not expose a dedicated
     * binlog-lock code (the failure uses the generic 400 code), so the narrowest
     * stable discriminator is the typed error/code plus the database filename
     * and one of TDLib's ownership phrases.
     */
    private const val TD_ERROR_BAD_REQUEST = 400
    private const val BINLOG_FILENAME = "td.binlog"
    private val LOCK_PHRASES = listOf("can't lock file", "cannot lock file")
    private val OWNERSHIP_PHRASES = listOf("already in use", "another program instance")
    private const val MAX_CAUSE_DEPTH = 10

    /** True when [error] (or any of its causes) is TDLib's binlog lock error. */
    fun isSessionLocked(error: Throwable?): Boolean =
        causes(error).filterIsInstance<TelegramError>().any { telegramError ->
            telegramError.errorCode == TD_ERROR_BAD_REQUEST &&
                isBinlogLockMessage(telegramError.errorMessage)
        }

    /** The message shown on stderr and to the MCP client. */
    fun describe(label: String, databaseDirectory: Path): String =
        "TDLib session for Telegram account '$label' is locked by another process: " +
            "${databaseDirectory.resolve("td.binlog")} is already in use. " +
            "A previous server instance is still running — stop it (for Docker: `docker ps` then " +
            "`docker stop <id>`) and start again. Two servers cannot share one session directory."

    private fun causes(error: Throwable?): Sequence<Throwable> =
        generateSequence(error) { previous -> previous.cause.takeIf { it !== previous } }
            .take(MAX_CAUSE_DEPTH)

    private fun isBinlogLockMessage(rawMessage: String?): Boolean {
        val message = rawMessage?.lowercase(Locale.ROOT) ?: return false
        return BINLOG_FILENAME in message &&
            LOCK_PHRASES.any(message::contains) &&
            OWNERSHIP_PHRASES.any(message::contains)
    }
}
