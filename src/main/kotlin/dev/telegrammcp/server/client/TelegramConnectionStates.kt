package dev.telegrammcp.server.client

import it.tdlight.client.SimpleTelegramClientBuilder
import it.tdlight.jni.TdApi
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * Last TDLib connection state (`updateConnectionState`) per account.
 *
 * The circuit breaker uses it to tell a flood-waited request (TDLib is
 * connected and simply holds the request) from a TDLib that cannot reach
 * Telegram. `ConnectionStateReady` and `ConnectionStateUpdating` (connected,
 * catching up on updates missed while offline) count as reachable;
 * `WaitingForNetwork`, `ConnectingToProxy` and `Connecting` do not. An
 * account with no state reported yet counts as not reachable.
 *
 * The state belongs to one client: [attach] for a new client and [clear]
 * when a client closes forget the previous state, and late updates from a
 * replaced client are ignored, so a closed client's last `Ready` never
 * vouches for its successor.
 */
@Component
class TelegramConnectionStates {
    private val states = ConcurrentHashMap<String, TdApi.ConnectionState>()

    /** The client currently allowed to report [account]'s state (one token per [attach]). */
    private val owners = ConcurrentHashMap<String, Any>()

    /**
     * Feeds the connection updates of the client built from [builder] into
     * [account]'s state. The account starts with no state; updates from a
     * client attached earlier are ignored from now on.
     */
    fun attach(builder: SimpleTelegramClientBuilder, account: String) {
        val owner = Any()
        synchronized(this) {
            owners[account] = owner
            states.remove(account)
        }
        builder.addUpdateHandler(TdApi.UpdateConnectionState::class.java) { update(account, owner, it.state) }
    }

    /** Forgets [account]'s state when its client closes; later updates of that client are ignored. */
    fun clear(account: String) {
        synchronized(this) {
            owners.remove(account)
            states.remove(account)
        }
    }

    /** Records [state] for [account] directly (tests and single-client callers). */
    fun update(account: String, state: TdApi.ConnectionState) {
        states[account] = state
    }

    private fun update(account: String, owner: Any, state: TdApi.ConnectionState) {
        synchronized(this) {
            if (owners[account] === owner) states[account] = state
        }
    }

    fun isReady(account: String): Boolean = states[account] is TdApi.ConnectionStateReady

    /** True while TDLib is connected to Telegram: Ready, or Updating after a reconnect. */
    fun isReachable(account: String): Boolean = when (states[account]) {
        is TdApi.ConnectionStateReady, is TdApi.ConnectionStateUpdating -> true
        else -> false
    }

    /** The state name (for example `ConnectionStateReady`), or null if none was reported. */
    fun stateName(account: String): String? = states[account]?.javaClass?.simpleName
}
