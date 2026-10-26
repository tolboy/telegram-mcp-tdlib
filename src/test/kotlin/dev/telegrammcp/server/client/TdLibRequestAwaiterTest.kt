package dev.telegrammcp.server.client

import dev.telegrammcp.server.exception.SendOutcomeUnknownException
import dev.telegrammcp.server.exception.TelegramApiException
import it.tdlight.client.TelegramError
import it.tdlight.jni.TdApi
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TdLibRequestAwaiterTest {
    @Test
    fun `send and forward timeouts do not imply failed delivery`() {
        val requests = listOf(
            TdApi.SendMessage().apply { chatId = 42 },
            TdApi.ForwardMessages().apply { chatId = 42 },
        )
        requests.forEach { request ->
            val pending = CompletableFuture<TdApi.Object>()
            val error = assertFailsWith<SendOutcomeUnknownException> {
                TdLibRequestAwaiter.await(pending, request, 0)
            }
            assertEquals(42L, error.chatId)
            assertTrue(error.cause is TimeoutException)
            // A local timeout must not prevent a later Telegram completion.
            assertTrue(pending.complete(TdApi.Ok()))
        }
    }

    @Test
    fun `read timeout stays a read failure`() {
        assertFailsWith<TimeoutException> {
            TdLibRequestAwaiter.await(CompletableFuture(), TdApi.GetMe(), 0)
        }
    }

    @Test
    fun `explicit upstream rejection is preserved`() {
        val rejected = IllegalStateException("rejected")
        val pending = CompletableFuture<TdApi.Object>().apply { completeExceptionally(rejected) }
        val error = assertFailsWith<IllegalStateException> {
            TdLibRequestAwaiter.await(pending, TdApi.SendMessage(), 0)
        }
        assertSame(rejected, error)
    }

    @Test
    fun `interrupted send preserves interrupt status and unknown outcome`() {
        try {
            Thread.currentThread().interrupt()
            assertFailsWith<SendOutcomeUnknownException> {
                TdLibRequestAwaiter.await(CompletableFuture(), TdApi.SendMessage(), 1)
            }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `telegram rejection becomes a classifiable TelegramApiException keeping the cause`() {
        val rejected = TelegramError(TdApi.Error(429, "Too Many Requests: retry after 33"))
        val pending = CompletableFuture<TdApi.Object>().apply { completeExceptionally(rejected) }
        val error = assertFailsWith<TelegramApiException> {
            TdLibRequestAwaiter.await(pending, TdApi.SendMessage(), 0)
        }
        assertEquals(429, error.errorCode)
        assertEquals("Too Many Requests: retry after 33", error.errorMessage)
        assertEquals(33L, error.retryAfterSeconds)
        assertSame(rejected, error.cause)
    }

    @Test
    fun `read-only detection drives retryability of transient failures`() {
        fun translated(request: TdApi.Function<*>) = TdLibRequestAwaiter.translate(
            TelegramError(TdApi.Error(500, "Timeout expired")), request,
        ) as TelegramApiException

        assertTrue(translated(TdApi.GetChatHistory()).isRetryable)
        assertTrue(translated(TdApi.SearchChatMessages()).isRetryable)
        assertTrue(translated(TdApi.LoadChats()).isRetryable)
        assertFalse(translated(TdApi.SendMessage()).isRetryable)
        assertFalse(translated(TdApi.CheckAuthenticationCode()).isRetryable, "check* functions can be mutations")
        assertFalse(translated(TdApi.GetCallbackQueryAnswer()).isRetryable, "pressing a bot button is a write")
    }

    @Test
    fun `retryability comes from the explicit allowlist, not from the function name`() {
        fun translated(request: TdApi.Function<*>) = TdLibRequestAwaiter.translate(
            TelegramError(TdApi.Error(500, "Timeout expired")), request,
        ) as TelegramApiException

        assertTrue(translated(TdApi.GetMessage()).isRetryable)
        assertTrue(translated(TdApi.DownloadFile()).isRetryable)
        assertFalse(translated(TdApi.RecognizeSpeech()).isRetryable, "speech recognition consumes a server-side quota")
        assertFalse(translated(TdApi.GetLoginUrl()).isRetryable, "authorizes a login on a website")
        assertFalse(translated(TdApi.GetInlineQueryResults()).isRetryable, "queries a bot on the user's behalf")
        assertFalse(translated(TdApi.GetStory()).isRetryable, "functions not on the allowlist are writes")
        assertFalse(TdLibRequestAwaiter.isReadOnly(TdApi.SearchStickers()), "a Search prefix alone is not enough")
    }

    @Test
    fun `every Get, Search and Load request the client sends is classified deliberately`() {
        val source = java.nio.file.Files.readString(
            java.nio.file.Path.of("src/main/kotlin/dev/telegrammcp/server/client/TdLibClientService.kt"),
        )
        val used = Regex("""TdApi\.((?:Get|Search|Load)[A-Za-z]+)\(""").findAll(source).map { it.groupValues[1] }
            .filter { TdApi.Function::class.java.isAssignableFrom(Class.forName("it.tdlight.jni.TdApi\$$it")) }
            .toSet()
        assertTrue("GetChatHistory" in used && "SearchMessagesFilterPinned" !in used)
        val knownWrites = setOf("GetCallbackQueryAnswer")
        val unclassified = used - knownWrites - TelegramReadRequests.names
        assertTrue(unclassified.isEmpty(), "add these to TelegramReadRequests or to knownWrites: $unclassified")
        assertFalse("RecognizeSpeech" in TelegramReadRequests.names)
        assertFalse("GetCallbackQueryAnswer" in TelegramReadRequests.names)
    }
}
