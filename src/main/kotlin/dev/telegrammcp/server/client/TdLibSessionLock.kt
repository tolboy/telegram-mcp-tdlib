package dev.telegrammcp.server.client

import java.nio.file.Path

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

    private const val LOCK_MARKER = "can't lock file"
    private const val IN_USE_MARKER = "already in use"
    private const val MAX_CAUSE_DEPTH = 10

    /** True when [error] (or any of its causes) is TDLib's binlog lock error. */
    fun isSessionLocked(error: Throwable?): Boolean =
        causes(error).any { throwable ->
            val message = throwable.message?.lowercase() ?: return@any false
            LOCK_MARKER in message && IN_USE_MARKER in message
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
}
