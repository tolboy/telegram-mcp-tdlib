package dev.telegrammcp.server.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.auth.RuntimeCredentialsHolder
import dev.telegrammcp.server.auth.TelegramAuthOrchestrator
import dev.telegrammcp.server.auth.TelegramAuthStateHolder
import dev.telegrammcp.server.auth.AuthState
import dev.telegrammcp.server.client.DelegatingTelegramClientService
import dev.telegrammcp.server.client.ExpectedAccountCheck
import dev.telegrammcp.server.client.AccountCheckResult
import dev.telegrammcp.server.client.MessageSendTracker
import dev.telegrammcp.server.client.TdLibClientServiceFactory
import dev.telegrammcp.server.client.TelegramConnectionStates
import dev.telegrammcp.server.config.TdLibProperties
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.ratelimiter.RateLimiter
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import it.tdlight.client.GenericUpdateHandler
import it.tdlight.client.SimpleTelegramClient
import it.tdlight.client.SimpleTelegramClientBuilder
import it.tdlight.jni.TdApi
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The change journal records nothing for a session the expected-account check has not accepted. */
class ChangeJournalAttachmentTest {
    @TempDir lateinit var root: Path
    private val mapper = jacksonObjectMapper()
    private val closeables = mutableListOf<AutoCloseable>()
    private val orchestrators = mutableListOf<TelegramAuthOrchestrator>()

    @AfterEach fun close() {
        orchestrators.forEach { it.shutdownLifecycleExecutor() }
        closeables.forEach { it.close() }
    }

    private fun journal(): ChangeJournal {
        val paths = mockk<PlatformPaths>()
        every { paths.applicationDataDirectory } answers { root }
        return ChangeJournal(paths, mapper).also { closeables += it }
    }

    private class Handlers(val builder: SimpleTelegramClientBuilder) {
        val newMessage = slot<GenericUpdateHandler<TdApi.UpdateNewMessage>>()
        val edited = slot<GenericUpdateHandler<TdApi.UpdateMessageEdited>>()
        val connection = slot<GenericUpdateHandler<TdApi.UpdateConnectionState>>()
        init {
            every { builder.addUpdateHandler(TdApi.UpdateNewMessage::class.java, capture(newMessage)) } returns Unit
            every { builder.addUpdateHandler(TdApi.UpdateMessageEdited::class.java, capture(edited)) } returns Unit
            every { builder.addUpdateHandler(TdApi.UpdateConnectionState::class.java, capture(connection)) } returns Unit
        }
        fun message(chatId: Long, id: Long) = newMessage.captured.onUpdate(TdApi.UpdateNewMessage(TdApi.Message().apply {
            this.chatId = chatId
            this.id = id
        }))
    }

    private fun handlers() = Handlers(mockk(relaxed = true))

    private fun ChangeJournal.since(cursor: String) = page("default", cursor, 200) { true }.entries

    @Test
    fun `an attachment that is not recording drops every update`() {
        val journal = journal()
        val cursor = journal.page("default", null, 10) { true }.nextCursor
        val hooks = handlers()
        val attachment = journal.attach(hooks.builder, "default", recording = false)

        hooks.message(42, 1)
        hooks.edited.captured.onUpdate(TdApi.UpdateMessageEdited(42, 1, 5, null))
        hooks.connection.captured.onUpdate(TdApi.UpdateConnectionState(TdApi.ConnectionStateConnecting()))

        assertFalse(attachment.recording)
        assertTrue(journal.since(cursor).isEmpty(), "pending verification does not write even a gap marker")
    }

    @Test
    fun `activation adds one gap marker and then records`() {
        val journal = journal()
        val hooks = handlers()
        val attachment = journal.attach(hooks.builder, "default", recording = false)
        hooks.message(42, 1)
        val cursor = journal.page("default", null, 200) { true }.nextCursor

        attachment.activate()
        attachment.activate()
        hooks.message(42, 2)

        val entries = journal.since(cursor)
        assertEquals(listOf(ChangeJournal.GAP, "new"), entries.map { it.kind })
        assertEquals(2L, entries.last().messageId)
        assertTrue(attachment.recording)
    }

    @Test
    fun `a default attachment records at once as before`() {
        val journal = journal()
        val cursor = journal.page("default", null, 10) { true }.nextCursor
        val hooks = handlers()
        assertTrue(journal.attach(hooks.builder, "default").recording)
        hooks.message(42, 1)
        assertEquals(listOf(ChangeJournal.GAP, "new"), journal.since(cursor).map { it.kind })
    }

    private fun orchestrator(journal: ChangeJournal, expectedUserId: Long?): Pair<TelegramAuthOrchestrator, DelegatingTelegramClientService> {
        val meters = SimpleMeterRegistry()
        val delegating = DelegatingTelegramClientService.wrapping(mockk(relaxed = true))
        val orchestrator = TelegramAuthOrchestrator(
            props = TdLibProperties(
                auth = TdLibProperties.Auth(expectedUserId = expectedUserId),
                database = TdLibProperties.Database(directory = root.resolve("tdlib").toString()),
            ),
            authStateHolder = authState,
            runtimeCredentials = RuntimeCredentialsHolder(),
            rateLimiter = RateLimiter.ofDefaults("journal-test"),
            circuitBreaker = CircuitBreaker.ofDefaults("journal-test"),
            meterRegistry = meters,
            delegatingClient = delegating,
            platformPaths = PlatformPaths(),
            artifactOwnership = AccountArtifactOwnership(mockk { every { applicationDataDirectory } returns root }),
            changeJournal = journal,
            clientServices = TdLibClientServiceFactory(TelegramConnectionStates(), meters),
        )
        orchestrators += orchestrator
        return orchestrator to delegating
    }

    private val authState = TelegramAuthStateHolder()

    private fun client(userId: Long): SimpleTelegramClient {
        val client = mockk<SimpleTelegramClient>(relaxed = true)
        every { client.send(any<TdApi.GetMe>()) } returns CompletableFuture.completedFuture(TdApi.User().apply {
            id = userId
            phoneNumber = ""
        })
        every { client.send(any<TdApi.SearchPublicChat>()) } returns CompletableFuture.failedFuture(IllegalStateException("offline"))
        return client
    }

    private fun awaitTrue(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(20)
        assertTrue(condition())
    }

    @Test
    fun `the login wizard never journals a refused account and stays up in an error state`() {
        val journal = journal()
        val (orchestrator, _) = orchestrator(journal, expectedUserId = 4242)
        val hooks = handlers()
        orchestrator.configureBuilder(hooks.builder, MessageSendTracker(), "")
        val attachment = assertNotNull(orchestrator.journalAttachment)
        assertFalse(attachment.recording)
        val cursor = journal.page("default", null, 200) { true }.nextCursor

        orchestrator.hotSwap(client(9999))
        awaitTrue { authState.getState() is AuthState.Error }
        hooks.message(777000, 1)
        hooks.message(42, 2)

        assertFalse(attachment.recording)
        assertTrue(journal.since(cursor).isEmpty(), "nothing from the refused account")
    }

    @Test
    fun `the login wizard starts journaling with a gap marker once the account is accepted`() {
        val journal = journal()
        val (orchestrator, _) = orchestrator(journal, expectedUserId = 4242)
        val hooks = handlers()
        orchestrator.configureBuilder(hooks.builder, MessageSendTracker(), "")
        hooks.message(42, 1)
        val cursor = journal.page("default", null, 200) { true }.nextCursor

        orchestrator.hotSwap(client(4242))
        val attachment = assertNotNull(orchestrator.journalAttachment)
        awaitTrue { attachment.recording }
        hooks.message(42, 2)

        assertEquals(listOf(ChangeJournal.GAP, "new"), journal.since(cursor).map { it.kind })
    }

    @Test
    fun `a matching wizard login stays closed when existing artifacts need adoption`() {
        val journal = journal()
        journal.append("default", "new", 42, 1)
        val cursor = journal.tailCursor("default")
        val (orchestrator, _) = orchestrator(journal, expectedUserId = 4242)
        Files.createDirectories(root.resolve("tdlib"))
        val hooks = handlers()
        orchestrator.configureBuilder(hooks.builder, MessageSendTracker(), "")
        val attachment = assertNotNull(orchestrator.journalAttachment)

        orchestrator.hotSwap(client(4242))
        awaitTrue { authState.getState() is AuthState.Error }
        hooks.message(42, 2)

        assertFalse(attachment.recording)
        assertTrue(journal.since(cursor).isEmpty())
        assertEquals(AccountCheckResult.ARTIFACTS_REFUSED, ExpectedAccountCheck.read(root.resolve("tdlib"))?.result)
    }

    @Test
    fun `without an expected account the wizard journals from the start`() {
        val journal = journal()
        val (orchestrator, _) = orchestrator(journal, expectedUserId = null)
        val hooks = handlers()
        orchestrator.configureBuilder(hooks.builder, MessageSendTracker(), "")
        assertTrue(assertNotNull(orchestrator.journalAttachment).recording)
    }
}
