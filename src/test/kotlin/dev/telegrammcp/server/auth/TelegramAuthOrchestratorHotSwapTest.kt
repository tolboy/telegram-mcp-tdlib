package dev.telegrammcp.server.auth

import dev.telegrammcp.server.client.DelegatingTelegramClientService
import dev.telegrammcp.server.client.MessageSendTracker
import dev.telegrammcp.server.client.TdLibClientServiceFactory
import dev.telegrammcp.server.client.TelegramConnectionStates
import dev.telegrammcp.server.config.TdLibProperties
import dev.telegrammcp.server.service.PlatformPaths
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.ratelimiter.RateLimiter
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import it.tdlight.client.GenericUpdateHandler
import it.tdlight.client.SimpleTelegramClientBuilder
import it.tdlight.jni.TdApi
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The auth-wizard hot-swap builds its live service through the shared factory. */
class TelegramAuthOrchestratorHotSwapTest {

    private val states = TelegramConnectionStates()
    private val meters = SimpleMeterRegistry()
    private val delegating = DelegatingTelegramClientService.wrapping(mockk(relaxed = true))
    private val orchestrator = TelegramAuthOrchestrator(
        props = TdLibProperties(),
        authStateHolder = TelegramAuthStateHolder(),
        runtimeCredentials = RuntimeCredentialsHolder(),
        rateLimiter = RateLimiter.ofDefaults("hot-swap-test"),
        circuitBreaker = CircuitBreaker.ofDefaults("hot-swap-test"),
        meterRegistry = meters,
        delegatingClient = delegating,
        platformPaths = PlatformPaths(),
        clientServices = TdLibClientServiceFactory(states, meters),
    )

    /** Builds the wizard's client handlers and returns its connection-state handler. */
    private fun connectionHandler(): GenericUpdateHandler<TdApi.UpdateConnectionState> {
        val builder = mockk<SimpleTelegramClientBuilder>(relaxed = true)
        val handler = slot<GenericUpdateHandler<TdApi.UpdateConnectionState>>()
        every { builder.addUpdateHandler(TdApi.UpdateConnectionState::class.java, capture(handler)) } returns Unit
        orchestrator.configureBuilder(builder, MessageSendTracker(), "")
        return handler.captured
    }

    @Test
    fun `a hot-swapped service has the timeout health and the connection state of its account`() {
        val connection = connectionHandler()
        val service = orchestrator.hotSwap(mockk(relaxed = true))
        assertSame(service, delegating.current())

        val health = service.timeoutHealth
        health.onDispatch()
        connection.onUpdate(TdApi.UpdateConnectionState(TdApi.ConnectionStateReady()))
        // Not alwaysCounting(): with the client's state Ready one flooded scope does not count.
        assertFalse(health.countsTowardBreaker("send chat 1"))
        assertTrue(states.isReady("default"))

        connection.onUpdate(TdApi.UpdateConnectionState(TdApi.ConnectionStateConnecting()))
        // The same health follows the client's connection state.
        assertTrue(health.countsTowardBreaker("send chat 1"))
    }
}
