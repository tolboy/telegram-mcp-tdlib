package dev.telegrammcp.server.client

import it.tdlight.ClientFactory
import it.tdlight.ExceptionHandler
import it.tdlight.Init
import it.tdlight.TelegramClient
import it.tdlight.UpdatesHandler
import it.tdlight.client.TelegramError
import it.tdlight.jni.TdApi
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Opt-in execution of the packaged JNI runtime and shared receiver lifecycle.
 * No SetTdlibParameters is sent: clients never leave WaitTdlibParameters, open
 * an account database, or attempt Telegram authentication/network access.
 */
@Tag("native")
class TdLibNativeLifecycleTest {
    @Test
    @Timeout(value = 45, unit = TimeUnit.SECONDS)
    fun `common receiver keeps offline clients independent across close and restart`() {
        Init.init()
        val callbackErrors = ConcurrentLinkedQueue<Throwable>()

        FactoryLease().use { firstLease ->
            FactoryLease().use { secondLease ->
                NativeClient(firstLease.factory.createClient(), callbackErrors).use { first ->
                    NativeClient(secondLease.factory.createClient(), callbackErrors).use { second ->
                        first.initialize()
                        second.initialize()
                        first.assertWaitingForParameters()
                        second.assertWaitingForParameters()
                        first.assertNativeVersion()
                        second.assertNativeVersion()

                        // The alarm is local TDLib work, not a Telegram request.
                        // Closing this client must complete its pending future.
                        val alarm = first.client.sendAsync(TdApi.SetAlarm(60.0))
                        assertFalse(alarm.isDone, "A 60-second local alarm must still be pending")
                        first.close()
                        first.close()
                        expectTelegramError(alarm)
                        first.assertClosedClient()
                        first.assertUpdateStreamStopped()

                        // One client's closure and one factory lease release
                        // must not shut down the receiver used by the other.
                        second.assertWaitingForParameters()
                        firstLease.close()
                        second.assertWaitingForParameters()
                        second.assertNativeVersion()
                        second.close()
                        second.assertClosedClient()
                    }
                }
            }
        }

        // Releasing the final lease stopped the underlying receiver. The same
        // public common-factory wrapper must create a fresh working receiver.
        FactoryLease().use { lease ->
            NativeClient(lease.factory.createClient(), callbackErrors).use { restarted ->
                restarted.initialize()
                restarted.assertWaitingForParameters()
                restarted.assertNativeVersion()
                restarted.close()
                restarted.assertClosedClient()
            }
        }
        assertTrue(callbackErrors.isEmpty(), "Native callback errors: ${callbackErrors.map { it.javaClass.simpleName }}")
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    fun `a failed client start leaves a later offline client usable`() {
        Init.init()
        val callbackErrors = ConcurrentLinkedQueue<Throwable>()
        val startup = runCatching {
            FactoryLease().use { lease ->
                NativeClient(lease.factory.createClient(), callbackErrors).use { failed ->
                    failed.initialize()
                    failed.assertWaitingForParameters()
                    error("startup failed after the native client was created")
                }
            }
        }
        assertIs<IllegalStateException>(startup.exceptionOrNull())

        FactoryLease().use { lease ->
            NativeClient(lease.factory.createClient(), callbackErrors).use { restarted ->
                restarted.initialize()
                restarted.assertWaitingForParameters()
                restarted.assertNativeVersion()
                restarted.close()
                restarted.assertClosedClient()
                restarted.assertUpdateStreamStopped()
            }
        }
        assertTrue(callbackErrors.isEmpty(), "Native callback errors: ${callbackErrors.map { it.javaClass.simpleName }}")
    }

    /** Common factory acquire calls return the same object; each acquire is released exactly once. */
    private class FactoryLease : AutoCloseable {
        val factory: ClientFactory = ClientFactory.acquireCommonClientFactory()
        private val released = AtomicBoolean()

        override fun close() {
            if (released.compareAndSet(false, true)) {
                // The API's blocking close has no timeout of its own.
                CompletableFuture.runAsync { factory.close() }.get(5, TimeUnit.SECONDS)
            }
        }
    }

    private class NativeClient(
        val client: TelegramClient,
        private val callbackErrors: ConcurrentLinkedQueue<Throwable>,
    ) : AutoCloseable {
        private val waitingForParameters = CountDownLatch(1)
        private val closed = CountDownLatch(1)
        private val updatesAfterClose = AtomicInteger()
        private val closeStarted = AtomicBoolean()
        private var initializationStarted = false

        fun initialize() {
            initializationStarted = true
            val failureHandler = ExceptionHandler { callbackErrors.add(it) }
            client.initialize(UpdatesHandler { updates ->
                var seenClosed = closed.count == 0L
                updates.filterIsInstance<TdApi.UpdateAuthorizationState>().forEach { update ->
                    if (seenClosed) updatesAfterClose.incrementAndGet()
                    when (update.authorizationState) {
                        is TdApi.AuthorizationStateWaitTdlibParameters -> waitingForParameters.countDown()
                        is TdApi.AuthorizationStateClosed -> {
                            closed.countDown()
                            seenClosed = true
                        }
                        else -> Unit
                    }
                }
            }, failureHandler, failureHandler)
            assertTrue(waitingForParameters.await(5, TimeUnit.SECONDS), "Native client did not request its parameters")
        }

        fun assertWaitingForParameters() {
            assertIs<TdApi.AuthorizationStateWaitTdlibParameters>(
                client.sendAsync(TdApi.GetAuthorizationState()).get(5, TimeUnit.SECONDS),
            )
        }

        fun assertNativeVersion() {
            val version = assertIs<TdApi.OptionValueString>(client.execute(TdApi.GetOption("version")))
            assertTrue(version.value.isNotBlank(), "JNI runtime did not report its version")
        }

        fun assertClosedClient() {
            // Closed updates run before TDLight transitions its Java handle to
            // closed. This bounded query also waits for that transition when
            // the test thread wakes during the final update callback.
            val error = expectTelegramError(client.sendAsync(TdApi.GetAuthorizationState()))
            assertTrue(error.errorCode in setOf(500, 503),
                "Unexpected closed-client error ${error.errorCode}: ${error.errorMessage}")
            assertIs<TdApi.Ok>(client.sendAsync(TdApi.Close()).get(5, TimeUnit.SECONDS))
        }

        fun assertUpdateStreamStopped() {
            Thread.sleep(200)
            assertEquals(0, updatesAfterClose.get(), "Authorization updates continued after Closed")
        }

        override fun close() {
            if (!initializationStarted) return
            if (!closeStarted.compareAndSet(false, true)) {
                assertTrue(closed.await(5, TimeUnit.SECONDS), "Repeated close raced the first Closed update")
                return
            }
            assertIs<TdApi.Ok>(client.sendAsync(TdApi.Close()).get(5, TimeUnit.SECONDS))
            assertTrue(closed.await(5, TimeUnit.SECONDS), "Native client did not publish its Closed update")
        }
    }

    private companion object {
        fun expectTelegramError(future: CompletableFuture<*>): TelegramError {
            val failure = org.junit.jupiter.api.assertThrows<ExecutionException> { future.get(5, TimeUnit.SECONDS) }
            return assertIs<TelegramError>(failure.cause)
        }
    }
}
