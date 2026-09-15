package dev.telegrammcp.server.client

import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.ratelimiter.RateLimiter
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import it.tdlight.client.GenericResultHandler
import it.tdlight.client.Result
import it.tdlight.client.SimpleTelegramClient
import it.tdlight.client.TelegramError
import it.tdlight.jni.TdApi
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TdLibMessageReactionsTest {
    private val client = mockk<SimpleTelegramClient>()
    private val requests = mutableListOf<TdApi.Function<*>>()
    private val service = TdLibClientService(
        client,
        RateLimiter.ofDefaults("reactions-test"),
        CircuitBreaker.ofDefaults("reactions-test"),
        SimpleMeterRegistry(),
    )

    private fun respond(handler: (TdApi.Function<*>) -> TdApi.Object) {
        every { client.send(any<TdApi.Function<TdApi.Object>>(), any<GenericResultHandler<TdApi.Object>>()) } answers {
            val request = firstArg<TdApi.Function<TdApi.Object>>()
            requests += request
            secondArg<GenericResultHandler<TdApi.Object>>().onResult(Result.of(handler(request)))
        }
    }

    private fun message(canList: Boolean): TdApi.Message = TdApi.Message().apply {
        interactionInfo = TdApi.MessageInteractionInfo().apply {
            reactions = TdApi.MessageReactions().apply {
                canGetAddedReactions = canList
                reactions = arrayOf(
                    TdApi.MessageReaction().apply {
                        type = TdApi.ReactionTypeEmoji("👍")
                        totalCount = 12
                        isChosen = true
                    },
                    TdApi.MessageReaction().apply {
                        type = TdApi.ReactionTypeCustomEmoji(123L)
                        totalCount = 3
                    },
                    TdApi.MessageReaction().apply {
                        type = TdApi.ReactionTypePaid()
                        totalCount = 5
                    },
                )
            }
        }
    }

    @Test
    fun `broadcast counts do not enumerate senders`() {
        respond { request ->
            assertTrue(request is TdApi.GetMessage)
            assertEquals(42L, request.chatId)
            assertEquals(100L, request.messageId)
            message(false)
        }

        val summary = service.getMessageReactions(42, 100)

        assertEquals(20, summary.totalCount)
        assertEquals(listOf("👍", "custom:123", "paid"), summary.reactionCounts.map { it.emoji })
        assertTrue(summary.reactionCounts.first().isChosen)
        assertTrue(summary.reactions.isEmpty())
        assertFalse(summary.canGetAddedReactions)
        assertEquals(1, requests.size)
    }

    @Test
    fun `stale permissions fall back on broadcast forbidden`() {
        respond { if (it is TdApi.GetMessage) message(true) else TdApi.Error(403, "BROADCAST_FORBIDDEN") }

        val summary = service.getMessageReactions(42, 100)

        assertEquals(20, summary.totalCount)
        assertFalse(summary.canGetAddedReactions)
        assertTrue(summary.reactions.isEmpty())
        assertEquals(2, requests.size)
    }

    @Test
    fun `missing interaction metadata still handles broadcast forbidden`() {
        respond { if (it is TdApi.GetMessage) TdApi.Message() else TdApi.Error(403, "BROADCAST_FORBIDDEN") }

        val summary = service.getMessageReactions(42, 100)

        assertEquals(0, summary.totalCount)
        assertFalse(summary.canGetAddedReactions)
    }

    @Test
    fun `other permission failures are propagated`() {
        respond { if (it is TdApi.GetMessage) message(true) else TdApi.Error(403, "CHAT_ACCESS_DENIED") }

        val error = assertFailsWith<TelegramError> { service.getMessageReactions(42, 100) }

        assertEquals("CHAT_ACCESS_DENIED", error.errorMessage)
    }

    @Test
    fun `message lookup errors are not hidden`() {
        respond { TdApi.Error(404, "MESSAGE_NOT_FOUND") }

        assertFailsWith<TelegramError> { service.getMessageReactions(42, 100) }
        assertEquals(1, requests.size)
    }

    @Test
    fun `allowed senders retain details and limit does not truncate aggregate counts`() {
        respond { request ->
            when (request) {
                is TdApi.GetMessage -> message(true)
                is TdApi.GetMessageAddedReactions -> {
                    assertEquals(100, request.limit)
                    assertEquals(42L, request.chatId)
                    assertEquals(100L, request.messageId)
                    TdApi.AddedReactions().apply {
                        reactions = arrayOf(TdApi.AddedReaction().apply {
                            type = TdApi.ReactionTypeEmoji("👍")
                            senderId = TdApi.MessageSenderUser(7L)
                            isOutgoing = true
                            date = 1000
                        })
                    }
                }
                is TdApi.GetUser -> TdApi.User().apply { firstName = "Alice" }
                else -> error("Unexpected request: $request")
            }
        }

        val summary = service.getMessageReactions(42, 100, 200)

        assertTrue(summary.canGetAddedReactions)
        assertEquals(20, summary.totalCount)
        val reaction = summary.reactions.single()
        assertEquals("Alice", reaction.senderName)
        assertEquals(7L, reaction.senderId)
        assertEquals("👍", reaction.emoji)
        assertTrue(reaction.isOutgoing)
        assertEquals(1000L, reaction.date?.epochSecond)
    }
}
