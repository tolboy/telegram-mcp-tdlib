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
import io.mockk.verify
import it.tdlight.client.ClientInteraction
import it.tdlight.client.GenericUpdateHandler
import it.tdlight.client.InputParameter
import it.tdlight.client.SimpleTelegramClient
import it.tdlight.client.SimpleTelegramClientBuilder
import it.tdlight.client.SimpleTelegramClientFactory
import it.tdlight.jni.TdApi
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.info.BuildProperties
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.util.Properties
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The auth-wizard hot-swap builds its live service through the shared factory. */
class TelegramAuthOrchestratorHotSwapTest {

    @TempDir
    lateinit var tempDir: Path

    private val states = TelegramConnectionStates()
    private val meters = SimpleMeterRegistry()
    private val fallback = mockk<dev.telegrammcp.server.client.TelegramClientService>(relaxed = true)
    private val delegating = DelegatingTelegramClientService.wrapping(fallback)
    private val authState = TelegramAuthStateHolder()
    private val credentials = RuntimeCredentialsHolder()
    private val orchestrator = TelegramAuthOrchestrator(
        props = TdLibProperties(),
        authStateHolder = authState,
        runtimeCredentials = credentials,
        rateLimiter = RateLimiter.ofDefaults("hot-swap-test"),
        circuitBreaker = CircuitBreaker.ofDefaults("hot-swap-test"),
        meterRegistry = meters,
        delegatingClient = delegating,
        platformPaths = PlatformPaths(),
        clientServices = TdLibClientServiceFactory(states, meters),
    )

    @AfterEach
    fun shutdown() = orchestrator.shutdownLifecycleExecutor()

    @Test
    fun `ready received before build completes still installs the live service`() {
        val authorization = authorizationHandler()
        authorization.onUpdate(TdApi.UpdateAuthorizationState(TdApi.AuthorizationStateReady()))
        assertTrue(authState.getState() is AuthState.Ready)
        assertSame(fallback, delegating.current())

        assertTrue(orchestrator.acceptBuiltClient(0, builtClient()))
        assertTrue(delegating.current() is dev.telegrammcp.server.client.TdLibClientService)
    }

    @Test
    fun `logout invalidates an in-flight build and ignores its late updates`() {
        val authorization = authorizationHandler()
        credentials.set(RuntimeCredentialsHolder.Credentials(123, "test-hash"))
        val pendingCode = authState.awaitCode()

        orchestrator.logout()
        assertTrue(authState.getState() is AuthState.LoggedOut)
        assertTrue(pendingCode.isCancelled)
        assertFalse(credentials.hasCredentials())
        assertFalse(orchestrator.acceptBuiltClient(0, builtClient()))
        authorization.onUpdate(TdApi.UpdateAuthorizationState(TdApi.AuthorizationStateReady()))
        authorization.onUpdate(TdApi.UpdateAuthorizationState(TdApi.AuthorizationStateClosed()))
        assertTrue(authState.getState() is AuthState.LoggedOut)
        assertSame(fallback, delegating.current())
    }

    @Test
    fun `late interaction cannot replace the current authentication prompt`() {
        val builder = mockk<SimpleTelegramClientBuilder>(relaxed = true)
        val interaction = slot<ClientInteraction>()
        every { builder.setClientInteraction(capture(interaction)) } returns Unit
        orchestrator.configureBuilder(builder, MessageSendTracker(), "")
        orchestrator.logout()

        val result = interaction.captured.onParameterRequest(InputParameter.ASK_PASSWORD, mockk())
        assertTrue(result.isCompletedExceptionally)
        assertTrue(authState.getState() is AuthState.LoggedOut)
        assertFalse(authState.submitPassword("stale"))
    }

    @Test
    fun `unexpected close detaches the live delegate and shutdown closes the factory`() {
        val authorization = authorizationHandler()
        val built = builtClient()
        orchestrator.acceptBuiltClient(0, built)
        authorization.onUpdate(TdApi.UpdateAuthorizationState(TdApi.AuthorizationStateReady()))
        assertTrue(delegating.current() is dev.telegrammcp.server.client.TdLibClientService)

        authorization.onUpdate(TdApi.UpdateAuthorizationState(TdApi.AuthorizationStateClosed()))
        assertTrue(authState.getState() is AuthState.Error)
        assertSame(fallback, delegating.current())
        verify(timeout = 2000, exactly = 1) { built.factory.close() }
    }

    private fun builtClient() = TelegramAuthOrchestrator.BuiltClient(
        mockk<SimpleTelegramClientFactory>(relaxed = true),
        mockk<SimpleTelegramClient>(relaxed = true),
        MessageSendTracker(),
    )

    private fun authorizationHandler(): GenericUpdateHandler<TdApi.UpdateAuthorizationState> {
        val builder = mockk<SimpleTelegramClientBuilder>(relaxed = true)
        val handler = slot<GenericUpdateHandler<TdApi.UpdateAuthorizationState>>()
        every { builder.addUpdateHandler(TdApi.UpdateAuthorizationState::class.java, capture(handler)) } returns Unit
        orchestrator.configureBuilder(builder, MessageSendTracker(), "")
        return handler.captured
    }

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

    @Test
    fun `recreating the wizard client forgets the old client's connection state`() {
        val oldConnection = connectionHandler()
        val health = orchestrator.hotSwap(mockk(relaxed = true)).timeoutHealth
        health.onDispatch()
        oldConnection.onUpdate(TdApi.UpdateConnectionState(TdApi.ConnectionStateReady()))
        assertTrue(states.isReachable("default"))

        // logout / a new initAuth tears the client down.
        orchestrator.destroyCurrentClient()
        assertFalse(states.isReachable("default"))
        assertTrue(health.countsTowardBreaker("send chat 1"))

        // The next client starts without a state; the old client's late updates are ignored.
        val newConnection = connectionHandler()
        oldConnection.onUpdate(TdApi.UpdateConnectionState(TdApi.ConnectionStateReady()))
        assertFalse(states.isReachable("default"))
        newConnection.onUpdate(TdApi.UpdateConnectionState(TdApi.ConnectionStateReady()))
        assertTrue(states.isReachable("default"))
    }

    @Test
    fun `wizard settings prepare private database and download directories before TDLib starts`() {
        val database = tempDir.resolve("session")
        val downloads = tempDir.resolve("downloads")
        val wizard = TelegramAuthOrchestrator(
            props = TdLibProperties(database = TdLibProperties.Database(directory = database.toString(), downloadsDirectory = downloads.toString())),
            authStateHolder = TelegramAuthStateHolder(),
            runtimeCredentials = RuntimeCredentialsHolder(),
            rateLimiter = RateLimiter.ofDefaults("wizard-directory-test"),
            circuitBreaker = CircuitBreaker.ofDefaults("wizard-directory-test"),
            meterRegistry = meters,
            delegatingClient = delegating,
            platformPaths = PlatformPaths(),
            buildProperties = BuildProperties(Properties().apply { setProperty("version", "test") }),
        )
        val buildSettings = TelegramAuthOrchestrator::class.java.getDeclaredMethod("buildSettings", Int::class.javaPrimitiveType, String::class.java)
        buildSettings.isAccessible = true
        buildSettings.invoke(wizard, 123, "test-hash")
        for (directory in listOf(database, downloads)) {
            assertTrue(Files.isDirectory(directory))
            if (Files.getFileAttributeView(directory, PosixFileAttributeView::class.java) != null) {
                assertEquals(
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE),
                    Files.getPosixFilePermissions(directory),
                )
            }
        }
    }
}
