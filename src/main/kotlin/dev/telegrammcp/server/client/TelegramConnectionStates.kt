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
 */
@Component
class TelegramConnectionStates {
    private val states = ConcurrentHashMap<String, TdApi.ConnectionState>()

    /** Feeds [account]'s connection updates into this holder. */
    fun attach(builder: SimpleTelegramClientBuilder, account: String) {
        builder.addUpdateHandler(TdApi.UpdateConnectionState::class.java) { update(account, it.state) }
    }

    fun update(account: String, state: TdApi.ConnectionState) {
        states[account] = state
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
