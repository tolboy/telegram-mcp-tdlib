package dev.telegrammcp.server.client

import dev.telegrammcp.server.config.TdLibProperties
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.ratelimiter.RateLimiter
import io.micrometer.core.instrument.MeterRegistry
import it.tdlight.client.SimpleTelegramClient
import it.tdlight.client.SimpleTelegramClientBuilder
import org.springframework.stereotype.Component

/**
 * The one place that builds a live [TdLibClientService].
 *
 * Both the configured accounts ([TdLibConfig]) and the auth-wizard hot-swap
 * (`TelegramAuthOrchestrator`) go through it, so every live service gets the
 * same per-account bulkhead and a [TdLibTimeoutHealth] fed by the account's
 * TDLib connection state. A service built without it would fall back to
 * [TdLibTimeoutHealth.alwaysCounting] and let one flood-waited chat open the
 * account's breaker.
 */
@Component
class TdLibClientServiceFactory(
    private val connectionStates: TelegramConnectionStates,
    private val meterRegistry: MeterRegistry,
) {
    /** Feeds the connection state of the client built from [builder] into [account]'s health. */
    fun attach(builder: SimpleTelegramClientBuilder, account: String) {
        connectionStates.attach(builder, account)
    }

    /** Forgets [account]'s connection state; call it when the account's client closes. */
    fun release(account: String) {
        connectionStates.clear(account)
    }

    /** Builds the live service of [account] on top of [client]. */
    fun create(
        account: String,
        client: SimpleTelegramClient,
        rateLimiter: RateLimiter,
        circuitBreaker: CircuitBreaker,
        requests: TdLibProperties.Requests,
        bulkheadName: String,
        chatFolderState: ChatFolderState = ChatFolderState(),
        messageSendTracker: MessageSendTracker? = null,
    ): TdLibClientService = TdLibClientService(
        client,
        rateLimiter,
        circuitBreaker,
        meterRegistry,
        chatFolderState,
        messageSendTracker,
        requestSettings = requests,
        // One account's backlog must not take every server thread.
        bulkhead = TdLibClientService.accountBulkhead(bulkheadName, requests),
        // A flood-waited chat must not open the account's breaker,
        // a disconnected or hung TDLib must.
        timeoutHealth = timeoutHealth(account, requests),
    )

    /** The breaker health of [account], read from its last reported connection state. */
    fun timeoutHealth(account: String, requests: TdLibProperties.Requests): TdLibTimeoutHealth = TdLibTimeoutHealth(
        connectionReachable = { connectionStates.isReachable(account) },
        distinctScopes = requests.breakerTimeoutScopes,
        noAnswerWindow = requests.breakerNoAnswerWindow,
    )
}
