package dev.telegrammcp.server.client

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.exception.InvalidToolInputException
import dev.telegrammcp.server.tool.message.InlineButtonTokens
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.ratelimiter.RateLimiter
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import it.tdlight.client.GenericResultHandler
import it.tdlight.client.Result
import it.tdlight.client.SimpleTelegramClient
import it.tdlight.jni.TdApi
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class InlineButtonCallbackTest {
    @Test
    fun `callbacks sign and send the original bytes even if their labels and text forms match`() {
        val client = mockk<SimpleTelegramClient>()
        val firstPayload = byteArrayOf(0x80.toByte(), 0, 99)
        val changedPayload = byteArrayOf(0x81.toByte(), 0, 99)
        var currentPayload = firstPayload
        val sent = mutableListOf<ByteArray>()
        every { client.send(any<TdApi.Function<TdApi.Object>>(), any<GenericResultHandler<TdApi.Object>>()) } answers {
            val response = when (val request = firstArg<TdApi.Function<TdApi.Object>>()) {
                is TdApi.GetMessages -> {
                    val button = TdApi.InlineKeyboardButton().apply {
                        text = "Approve"
                        type = TdApi.InlineKeyboardButtonTypeCallback(currentPayload)
                    }
                    val message = TdApi.Message().apply {
                        replyMarkup = TdApi.ReplyMarkupInlineKeyboard().apply { rows = arrayOf(arrayOf(button)) }
                    }
                    TdApi.Messages(1, arrayOf(message))
                }
                is TdApi.GetCallbackQueryAnswer -> {
                    sent += (request.payload as TdApi.CallbackQueryPayloadData).data
                    TdApi.CallbackQueryAnswer().apply { text = "Approved" }
                }
                else -> error("Unexpected request $request")
            }
            secondArg<GenericResultHandler<TdApi.Object>>().onResult(Result.of(response))
        }
        val service = TdLibClientService(
            client, RateLimiter.ofDefaults("button-test"), CircuitBreaker.ofDefaults("button-test"), SimpleMeterRegistry(),
        )
        val tokens = InlineButtonTokens()
        val original = service.listInlineButtons(42, 100).single()
        val token = tokens.tokenFor(42, 100, original)
        val rendered = jacksonObjectMapper().valueToTree<com.fasterxml.jackson.databind.JsonNode>(original)
        assertEquals(original.callbackData, rendered["callbackData"].asText())
        assertNull(rendered.get("callbackDataBase64"))

        currentPayload = changedPayload
        val changed = service.listInlineButtons(42, 100)
        assertEquals(original.callbackData, changed.single().callbackData)
        assertNull(tokens.find(token, 42, 100, changed))
        assertFailsWith<InvalidToolInputException> { service.pressInlineButton(42, 100, 0, null, original) }
        assertEquals(0, sent.size)

        currentPayload = firstPayload
        assertEquals("Approved", service.pressInlineButton(42, 100, 0, null, original))
        assertContentEquals(firstPayload, sent.single())
    }
}
