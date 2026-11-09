package dev.telegrammcp.server.client

import dev.telegrammcp.server.config.ResilienceConfig
import dev.telegrammcp.server.exception.TelegramApiException
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.ratelimiter.RateLimiter
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import it.tdlight.client.GenericResultHandler
import it.tdlight.client.Result
import it.tdlight.client.SimpleTelegramClient
import it.tdlight.jni.TdApi
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TdLibChatLoadingTest {
    private val client = mockk<SimpleTelegramClient>()
    private val requests = mutableListOf<String>()
    private val service = TdLibClientService(
        client, RateLimiter.ofDefaults("chat-loading"),
        CircuitBreaker.of("chat-loading", ResilienceConfig.ignoringNonHealthFailures(CircuitBreakerConfig.ofDefaults())),
        SimpleMeterRegistry(),
    )

    private fun loadFailure(code: Int, message: String) {
        every { client.send(any<TdApi.Function<TdApi.Object>>(), any<GenericResultHandler<TdApi.Object>>()) } answers {
            val request = firstArg<TdApi.Function<*>>()
            requests += request.javaClass.simpleName
            val response = when (request) {
                is TdApi.LoadChats -> TdApi.Error(code, message)
                is TdApi.GetChats -> TdApi.Chats(0, longArrayOf())
                else -> error("Unexpected request: ${request.javaClass.simpleName}")
            }
            secondArg<GenericResultHandler<TdApi.Object>>().onResult(Result.of(response))
        }
    }

    @Test
    fun `end of chat list still returns the cached inventory`() {
        loadFailure(404, "Not Found")
        assertTrue(service.getChats(20).isEmpty())
        assertEquals(listOf("LoadChats", "GetChats"), requests)
    }

    @Test
    fun `flood wait cannot produce a successful incomplete chat list`() {
        loadFailure(429, "Too Many Requests: retry after 17")
        val error = assertFailsWith<TelegramApiException> { service.getChats(20) }
        assertEquals(17L, error.retryAfterSeconds)
        assertEquals(listOf("LoadChats"), requests)
    }

    @Test
    fun `server failure cannot produce a successful incomplete chat list`() {
        loadFailure(500, "Internal Server Error")
        val error = assertFailsWith<TelegramApiException> { service.getChats(20) }
        assertEquals(500, error.errorCode)
        assertEquals(listOf("LoadChats"), requests)
    }
}
