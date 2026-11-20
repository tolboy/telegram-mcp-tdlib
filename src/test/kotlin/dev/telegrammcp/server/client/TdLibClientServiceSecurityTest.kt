package dev.telegrammcp.server.client

import dev.telegrammcp.server.exception.ChatNotAllowedException
import dev.telegrammcp.server.model.DownloadResult
import dev.telegrammcp.server.security.PermissionDeniedException
import dev.telegrammcp.server.tool.ToolError
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.ratelimiter.RateLimiter
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import it.tdlight.client.GenericResultHandler
import it.tdlight.client.Result as TdResult
import it.tdlight.client.SimpleTelegramClient
import it.tdlight.jni.TdApi
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TdLibClientServiceSecurityTest {

    private val client = mockk<SimpleTelegramClient>()

    @Test
    fun `public join rejects a freshly resolved chat that differs from the validated id`() {
        assertFailsWith<ChatNotAllowedException> {
            TdLibClientService.requireExpectedPublicChatId(
                actualChatId = 200,
                expectedChatId = 100,
            )
        }
    }

    @Test
    fun `public join accepts the exact validated chat id`() {
        TdLibClientService.requireExpectedPublicChatId(
            actualChatId = 100,
            expectedChatId = 100,
        )
    }

    @Test
    fun `protected and self-destructing message media is refused before download`() {
        for ((canBeSaved, selfDestructType) in listOf(false to null, true to TdApi.MessageSelfDestructTypeImmediately())) {
            val service = downloadService(canBeSaved = canBeSaved, selfDestructType = selfDestructType)
            val error = assertFailsWith<PermissionDeniedException> { service.downloadMedia(42, 80) }
            assertEquals("PERMISSION_DENIED", ToolError.from(error).code)
        }
        verify(exactly = 0) { client.send(any<TdApi.DownloadFile>(), any<GenericResultHandler<TdApi.File>>()) }
    }

    @Test
    fun `download does not return paths for incomplete files or empty paths`() {
        for (local in listOf(
            TdApi.LocalFile().apply { path = "cache/partial.bin" },
            TdApi.LocalFile().apply {
                isDownloadingCompleted = true
                path = ""
            },
        )) {
            val service = downloadService(local = local)
            val error = assertFailsWith<IllegalStateException> { service.downloadMedia(42, 80) }
            assertEquals("TDLib did not return a complete local media file", error.message)
        }
    }

    @Test
    fun `download returns the existing result for complete savable media`() {
        val service = downloadService(local = TdApi.LocalFile().apply {
            isDownloadingCompleted = true
            path = "cache/download.bin"
        })
        assertEquals(
            DownloadResult("cache/download.bin", "original.bin", "application/octet-stream", 512),
            service.downloadMedia(42, 80),
        )
    }

    private fun downloadService(
        canBeSaved: Boolean = true,
        selfDestructType: TdApi.MessageSelfDestructType? = null,
        local: TdApi.LocalFile? = null,
    ): TdLibClientService {
        val message = TdApi.Message().apply {
            chatId = 42
            id = 80
            this.canBeSaved = canBeSaved
            this.selfDestructType = selfDestructType
            content = TdApi.MessageDocument(
                TdApi.Document("original.bin", "application/octet-stream", null, null, TdApi.File().apply { id = 7 }),
                TdApi.FormattedText("", emptyArray()),
            )
        }
        every { client.send(any<TdApi.Function<TdApi.Object>>(), any<GenericResultHandler<TdApi.Object>>()) } answers {
            val result = when (firstArg<TdApi.Function<*>>()) {
                is TdApi.GetMessages -> TdApi.Messages(1, arrayOf(message))
                is TdApi.DownloadFile -> TdApi.File().apply {
                    id = 7
                    size = 512
                    this.local = local
                }
                else -> error("Unexpected TDLib call")
            }
            secondArg<GenericResultHandler<TdApi.Object>>().onResult(TdResult.of(result))
        }
        return TdLibClientService(
            client, RateLimiter.ofDefaults("media-security"), CircuitBreaker.ofDefaults("media-security"),
            SimpleMeterRegistry(),
        )
    }
}
