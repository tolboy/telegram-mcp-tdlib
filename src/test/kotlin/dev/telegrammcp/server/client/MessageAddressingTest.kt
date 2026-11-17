package dev.telegrammcp.server.client

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
import kotlin.test.*

class MessageAddressingTest {
    private val me = TdApi.User().apply {
        id = 1000
        usernames = TdApi.Usernames().apply {
            activeUsernames = arrayOf("Owner", "owner_alt")
            disabledUsernames = emptyArray()
            editableUsername = "Owner"
        }
    }
    private val group = TdApi.ChatTypeSupergroup(5, false)

    private fun text(value: String, vararg entities: TdApi.TextEntity) =
        TdApi.MessageText().apply { text = TdApi.FormattedText(value, arrayOf(*entities)) }

    private fun message(id: Long = 10, chatId: Long = -100, content: TdApi.MessageContent = text("hi"),
        outgoing: Boolean = false, unreadMention: Boolean = false, replyTo: TdApi.MessageReplyTo? = null) = TdApi.Message().apply {
        this.id = id
        this.chatId = chatId
        this.content = content
        this.isOutgoing = outgoing
        this.containsUnreadMention = unreadMention
        this.replyTo = replyTo
        this.senderId = TdApi.MessageSenderUser(77)
    }

    private fun reply(messageId: Long, chatId: Long = 0) = TdApi.MessageReplyToMessage().apply {
        this.chatId = chatId
        this.messageId = messageId
    }

    private fun classify(message: TdApi.Message, type: TdApi.ChatType = group, mine: Set<Long> = emptySet()) =
        MessageAddressingClassifier.classify(message, type, me, mine)

    @Test fun `private and secret chats are addressed but Saved Messages is not`() {
        assertTrue(classify(message(chatId = 77), TdApi.ChatTypePrivate(77)).privateChat)
        assertTrue(classify(message(chatId = 77), TdApi.ChatTypeSecret(3, 77)).privateChat)
        assertFalse(classify(message(chatId = 1000), TdApi.ChatTypePrivate(1000)).privateChat)
        assertFalse(classify(message()).privateChat)
    }

    @Test fun `username mentions match any of the account's names without case and ignore other users`() {
        val mine = "hey @OWNER and @owner_alt"
        assertTrue(classify(message(content = text(mine, TdApi.TextEntity(4, 6, TdApi.TextEntityTypeMention())))).mentionsMe)
        assertTrue(classify(message(content = text(mine, TdApi.TextEntity(15, 10, TdApi.TextEntityTypeMention())))).mentionsMe)
        assertFalse(classify(message(content = text("hey @someone", TdApi.TextEntity(4, 8, TdApi.TextEntityTypeMention())))).mentionsMe)
        // Plain text without a mention entity is not a mention, and broken offsets never throw.
        assertFalse(classify(message(content = text("owner"))).mentionsMe)
        assertFalse(classify(message(content = text("@Owner", TdApi.TextEntity(2, 40, TdApi.TextEntityTypeMention())))).mentionsMe)
    }

    @Test fun `text mentions by user ID and captions count`() {
        assertTrue(classify(message(content = text("Boss", TdApi.TextEntity(0, 4, TdApi.TextEntityTypeMentionName(1000))))).mentionsMe)
        assertFalse(classify(message(content = text("Bob", TdApi.TextEntity(0, 3, TdApi.TextEntityTypeMentionName(5))))).mentionsMe)
        val photo = TdApi.MessagePhoto().apply {
            caption = TdApi.FormattedText("@owner look", arrayOf(TdApi.TextEntity(0, 6, TdApi.TextEntityTypeMention())))
        }
        assertTrue(classify(message(content = photo)).mentionsMe)
    }

    @Test fun `replies count only when they target the account's own message in the same chat`() {
        assertEquals(5L, MessageAddressingClassifier.sameChatReplyTarget(message(replyTo = reply(5))))
        assertEquals(5L, MessageAddressingClassifier.sameChatReplyTarget(message(replyTo = reply(5, chatId = -100))))
        assertNull(MessageAddressingClassifier.sameChatReplyTarget(message(replyTo = reply(5, chatId = -200))))
        assertNull(MessageAddressingClassifier.sameChatReplyTarget(message(replyTo = TdApi.MessageReplyToStory(1, 2))))
        assertTrue(classify(message(replyTo = reply(5)), mine = setOf(5)).repliesToMe)
        assertFalse(classify(message(replyTo = reply(6)), mine = setOf(5)).repliesToMe)
    }

    @Test fun `Telegram's unread-mention flag is a mention unless a reply to the account explains it`() {
        assertTrue(classify(message(unreadMention = true)).mentionsMe)
        val replying = classify(message(unreadMention = true, replyTo = reply(5)), mine = setOf(5))
        assertTrue(replying.repliesToMe)
        assertFalse(replying.mentionsMe)
    }

    @Test fun `sender and direction are carried over`() {
        val facts = classify(message(outgoing = true).apply { senderId = TdApi.MessageSenderChat(-300) })
        assertTrue(facts.isOutgoing)
        assertEquals(-300L, facts.senderId)
        assertEquals(10L, facts.messageId)
        assertEquals(-100L, facts.chatId)
    }

    @Test fun `the adapter batches lookups, checks reply targets once and skips missing messages`() {
        val client = mockk<SimpleTelegramClient>()
        val requests = mutableListOf<TdApi.Function<*>>()
        every { client.send(any<TdApi.Function<TdApi.Object>>(), any<GenericResultHandler<TdApi.Object>>()) } answers {
            val request = firstArg<TdApi.Function<TdApi.Object>>()
            requests += request
            val response: TdApi.Object = when (request) {
                is TdApi.GetMe -> me
                is TdApi.GetChat -> TdApi.Chat().apply { id = -100; type = group }
                is TdApi.GetMessages -> TdApi.Messages(request.messageIds.size, request.messageIds.map { id ->
                    when (id) {
                        10L -> message(10, replyTo = reply(5))
                        11L -> message(11, replyTo = reply(6))
                        5L -> message(5, outgoing = true)
                        6L -> message(6)
                        else -> null
                    }
                }.toTypedArray())
                else -> error("unexpected $request")
            }
            secondArg<GenericResultHandler<TdApi.Object>>().onResult(Result.of(response))
        }
        val service = TdLibClientService(client, RateLimiter.ofDefaults("addressing-test"),
            CircuitBreaker.ofDefaults("addressing-test"), SimpleMeterRegistry())
        val facts = service.getMessageAddressing(-100, listOf(10, 11, 12, 10))
        assertEquals(listOf(10L, 11L), facts.map { it.messageId })
        assertEquals(listOf(true, false), facts.map { it.repliesToMe })
        val lookups = requests.filterIsInstance<TdApi.GetMessages>()
        assertEquals(listOf(listOf(10L, 11L, 12L), listOf(5L, 6L)), lookups.map { it.messageIds.toList() })
        assertTrue(service.getMessageAddressing(-100, emptyList()).isEmpty())
        assertFailsWith<IllegalArgumentException> { service.getMessageAddressing(-100, (1L..201L).toList()) }
    }
}
