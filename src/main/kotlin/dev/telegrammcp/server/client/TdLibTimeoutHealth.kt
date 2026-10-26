package dev.telegrammcp.server.client

import java.time.Duration

/**
 * Decides whether a full per-request timeout counts toward the account's
 * circuit breaker.
 *
 * TDLib parks a request in its flood-wait delayer for up to 60 s, so a single
 * flood-waited scope can time out request after request while TDLib itself is
 * fine. Counting those would let one busy chat open the breaker and block
 * every other operation of the account. A timeout therefore counts only when
 * one of these holds:
 * - the connection is not reachable ([connectionReachable] is false): TDLib
 *   is not connected to Telegram. Ready and Updating (connected, catching up
 *   on missed updates) are reachable; Connecting, ConnectingToProxy,
 *   WaitingForNetwork and an unknown state are not;
 * - the timeouts since TDLib last answered any request came from at least
 *   [distinctScopes] different flood-gate scopes: flood waits are per scope,
 *   so timeouts across scopes point at TDLib itself;
 * - TDLib has not answered any request for [noAnswerWindow] while requests
 *   were outstanding (measured from the first request sent after the last
 *   answer, so an idle account does not count as silent).
 *
 * The last two are the backstop for a TDLib that hangs but still reports
 * `ConnectionStateReady` (or Updating). Any answer from TDLib, including an error, resets
 * the streak.
 */
class TdLibTimeoutHealth(
    private val connectionReachable: () -> Boolean,
    private val distinctScopes: Int,
    private val noAnswerWindow: Duration,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    init {
        require(distinctScopes > 0) { "distinctScopes must be positive" }
        require(!noAnswerWindow.isNegative && !noAnswerWindow.isZero) { "noAnswerWindow must be positive" }
    }

    private var silentSinceNanos: Long? = null
    private val timedOutScopes = HashSet<String>()

    /** A request is about to be handed to TDLib. */
    @Synchronized
    fun onDispatch() {
        if (silentSinceNanos == null) silentSinceNanos = nanoTime()
    }

    /** TDLib answered a request (a result or an error). */
    @Synchronized
    fun onAnswer() {
        silentSinceNanos = null
        timedOutScopes.clear()
    }

    /** A request in [scope] ran into its full timeout; true if that counts toward the breaker. */
    @Synchronized
    fun countsTowardBreaker(scope: String): Boolean {
        if (timedOutScopes.size < distinctScopes) timedOutScopes += scope
        if (!connectionReachable()) return true
        if (timedOutScopes.size >= distinctScopes) return true
        val since = silentSinceNanos ?: return false
        return nanoTime() - since >= noAnswerWindow.toNanos()
    }

    companion object {
        /** Every full timeout counts (no connection state known); the behaviour without a health signal. */
        fun alwaysCounting(): TdLibTimeoutHealth = TdLibTimeoutHealth({ false }, 1, Duration.ofSeconds(1))
    }
}
