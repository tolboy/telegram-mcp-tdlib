package dev.telegrammcp.server.client

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.exception.InvalidToolInputException
import dev.telegrammcp.server.model.STALE_INLINE_BUTTON_MESSAGE
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
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

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
                is TdApi.GetMessages -> callbackMessage(0, currentPayload)
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

    @Test
    fun `a message edit after the final button check leaves the submitted callback unchanged`() {
        val client = mockk<SimpleTelegramClient>()
        val rateLimiter = mockk<RateLimiter>()
        val verifiedPayload = byteArrayOf(0x80.toByte(), 0, 99)
        val originalMessage = callbackMessage(5, verifiedPayload)
        val editedMessage = callbackMessage(6, byteArrayOf(0x81.toByte(), 0, 99))
        var currentMessage = originalMessage
        var keyboardReads = 0
        val sent = mutableListOf<ByteArray>()

        every { rateLimiter.reservePermission() } answers {
            // The callback reserves a permit after verification, before TDLib submission.
            if (keyboardReads == 2) currentMessage = editedMessage
            0L
        }
        every { client.send(any<TdApi.Function<TdApi.Object>>(), any<GenericResultHandler<TdApi.Object>>()) } answers {
            val response = when (val request = firstArg<TdApi.Function<TdApi.Object>>()) {
                is TdApi.GetMessages -> {
                    keyboardReads++
                    currentMessage
                }
                is TdApi.GetCallbackQueryAnswer -> {
                    assertSame(editedMessage, currentMessage)
                    sent += (request.payload as TdApi.CallbackQueryPayloadData).data
                    TdApi.CallbackQueryAnswer().apply { text = "Approved" }
                }
                else -> error("Unexpected request $request")
            }
            secondArg<GenericResultHandler<TdApi.Object>>().onResult(Result.of(response))
        }
        val service = TdLibClientService(
            client, rateLimiter, CircuitBreaker.ofDefaults("button-test"), SimpleMeterRegistry(),
        )

        val original = service.listInlineButtons(42, 100).single()
        assertEquals("Approved", service.pressInlineButton(42, 100, 0, null, original))

        assertEquals(2, keyboardReads)
        assertContentEquals(verifiedPayload, sent.single())
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1])
    fun `a signed button that disappears before the final check requires relisting`(remainingButtons: Int) {
        val client = mockk<SimpleTelegramClient>()
        val firstPayload = byteArrayOf(1)
        var currentMessage = callbackMessage(5, firstPayload, byteArrayOf(2))
        every { client.send(any<TdApi.Function<TdApi.Object>>(), any<GenericResultHandler<TdApi.Object>>()) } answers {
            val request = firstArg<TdApi.Function<TdApi.Object>>()
            check(request is TdApi.GetMessages) { "Unexpected request $request" }
            secondArg<GenericResultHandler<TdApi.Object>>().onResult(Result.of(currentMessage))
        }
        val service = TdLibClientService(
            client, RateLimiter.ofDefaults("button-test"), CircuitBreaker.ofDefaults("button-test"), SimpleMeterRegistry(),
        )
        val selected = service.listInlineButtons(42, 100)[1]
        assertFailsWith<InvalidToolInputException> {
            service.pressInlineButton(42, 100, 0, null, selected)
        }
        currentMessage = if (remainingButtons == 0) callbackMessage(6) else callbackMessage(6, firstPayload)

        val stale = assertFailsWith<InvalidToolInputException> {
            service.pressInlineButton(42, 100, selected.position, null, selected)
        }
        assertEquals(STALE_INLINE_BUTTON_MESSAGE, stale.message)

        if (remainingButtons == 0) {
            val legacy = assertFailsWith<IllegalStateException> { service.pressInlineButton(42, 100, 1, null) }
            assertEquals("Message has no inline keyboard", legacy.message)
        } else {
            val legacy = assertFailsWith<IllegalArgumentException> { service.pressInlineButton(42, 100, 1, null) }
            assertEquals("button_index 1 out of range (0..0)", legacy.message)
        }
    }

    private fun callbackMessage(editDate: Int, vararg payloads: ByteArray): TdApi.Messages {
        val message = TdApi.Message().apply {
            this.editDate = editDate
            if (payloads.isNotEmpty()) {
                val buttons = payloads.map { payload ->
                    TdApi.InlineKeyboardButton().apply {
                        text = "Approve"
                        type = TdApi.InlineKeyboardButtonTypeCallback(payload)
                    }
                }.toTypedArray()
                replyMarkup = TdApi.ReplyMarkupInlineKeyboard().apply { rows = arrayOf(buttons) }
            }
        }
        return TdApi.Messages(1, arrayOf(message))
    }
}
