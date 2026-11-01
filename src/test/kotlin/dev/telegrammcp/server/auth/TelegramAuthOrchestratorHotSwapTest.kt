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
