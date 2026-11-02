package dev.telegrammcp.server.resource

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.modelcontextprotocol.spec.McpSchema
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.TestingAuthenticationToken
import reactor.core.publisher.Mono
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResourceUpdateNotifierTest {
    private class RecordingSink(val watched: Set<String>? = null, val gapUris: Set<String> = emptySet()) : ResourceUpdateSink {
        val delivered = mutableListOf<String>()
        val sources = mutableMapOf<String, Set<Long>?>()
        override fun isWatched(uri: String) = watched == null || uri in watched
        override fun watchedForAccount(account: String) = gapUris
        override fun deliver(uri: String, sourceChats: Set<Long>?) { delivered += uri; sources[uri] = sourceChats }
    }

    private val second = TimeUnit.SECONDS.toNanos(1)

    @Test fun `journal kinds map to the resources whose content they change`() {
        val notifier = ResourceUpdateNotifier(RecordingSink(gapUris = setOf("telegram://a/chat/9")), second)
        val chat = "telegram://a/chat/42"
        val chats = "telegram://a/chats"
        val message = "telegram://a/chat/42/message/7"
        assertEquals(setOf(chat, chats), notifier.urisFor("a", "new", 42, 7))
        assertEquals(setOf(chat, message), notifier.urisFor("a", "edited", 42, 7))
        assertEquals(setOf(chat, message), notifier.urisFor("a", "content_changed", 42, 7))
        assertEquals(setOf(chat, message, chats), notifier.urisFor("a", "deleted", 42, 7))
        assertEquals(setOf("telegram://a/chat/9"), notifier.urisFor("a", "coverage_gap", null, null))
        assertEquals(emptySet(), notifier.urisFor("a", "something_else", 42, 7))
    }

    @Test fun `bursts coalesce into one notification per URI after the debounce window`() {
        val sink = RecordingSink()
        val notifier = ResourceUpdateNotifier(sink, second)
        repeat(50) { notifier.enqueue(listOf("telegram://a/chat/42", "telegram://a/chats"), it * 1_000L) }
        notifier.flush(second - 1)
        assertTrue(sink.delivered.isEmpty())
        notifier.flush(second)
        assertEquals(listOf("telegram://a/chat/42", "telegram://a/chats"), sink.delivered)
        notifier.enqueue(listOf("telegram://a/chat/42"), second + 5)
        notifier.flush(2 * second + 5)
        assertEquals(3, sink.delivered.size)
    }

    @Test fun `unwatched URIs are never queued and the backlog is bounded`() {
        val sink = RecordingSink(watched = setOf("telegram://a/chat/1", "telegram://a/chat/2", "telegram://a/chat/3"))
        val notifier = ResourceUpdateNotifier(sink, 0, maxPending = 2)
        notifier.enqueue(listOf("telegram://a/chat/9", "telegram://a/chat/1", "telegram://a/chat/2", "telegram://a/chat/3"), 0)
        assertEquals(2, notifier.pendingCount())
        notifier.flush(0)
        assertEquals(listOf("telegram://a/chat/1", "telegram://a/chat/2"), sink.delivered)
    }

    @Test fun `a failing delivery does not stop the others`() {
        val delivered = mutableListOf<String>()
        val notifier = ResourceUpdateNotifier(object : ResourceUpdateSink {
            override fun isWatched(uri: String) = true
            override fun watchedForAccount(account: String) = emptySet<String>()
            override fun deliver(uri: String, sourceChats: Set<Long>?) { if (uri.endsWith("/1")) error("boom"); delivered += uri }
        }, 0)
        notifier.enqueue(listOf("telegram://a/chat/1", "telegram://a/chat/2"), 0)
        notifier.flush(0)
        assertEquals(listOf("telegram://a/chat/2"), delivered)
    }

    @Test fun `HTTP delivery re-authorizes each subscribed session with its own identity`() {
        val registry = ResourceSubscriptionRegistry()
        val alice = TestingAuthenticationToken("alice", null, "ROLE_X")
        val mallory = TestingAuthenticationToken("mallory", null, "ROLE_X")
        registry.add("s-alice", alice, "telegram://a/chat/42")
        registry.add("s-mallory", mallory, "telegram://a/chat/42")
        val access = mockk<TelegramResourceAccess>()
        every { access.permits(any(), alice, any()) } returns true
        every { access.permits(any(), mallory, any()) } returns false
        val transport = mockk<McpStreamableServerTransportProvider>()
        every { transport.notifyClient(any(), any(), any()) } returns Mono.empty()
        HttpResourceUpdateSink(registry, access) { transport }.deliver("telegram://a/chat/42")
        verify(exactly = 1) {
            transport.notifyClient("s-alice", McpSchema.METHOD_NOTIFICATION_RESOURCES_UPDATED,
                McpSchema.ResourcesUpdatedNotification("telegram://a/chat/42"))
        }
        verify(exactly = 0) { transport.notifyClient("s-mallory", any(), any()) }
    }

    @Test fun `stdio delivery checks the local identity and leaves subscriber matching to the SDK`() {
        val access = mockk<TelegramResourceAccess>()
        every { access.permits(TelegramResourceUri.Chat("a", 42), null, any()) } returns true
        every { access.permits(TelegramResourceUri.Chat("a", 43), null, any()) } returns false
        val server = mockk<io.modelcontextprotocol.server.McpSyncServer>(relaxed = true)
        val sink = StdioResourceUpdateSink(access) { server }
        assertTrue(sink.isWatched("telegram://a/chat/43"))
        assertEquals(setOf("telegram://a/chats"), sink.watchedForAccount("a"))
        sink.deliver("telegram://a/chat/42")
        sink.deliver("telegram://a/chat/43")
        sink.deliver("not-a-uri")
        verify(exactly = 1) { server.notifyResourcesUpdated(any()) }
        verify { server.notifyResourcesUpdated(McpSchema.ResourcesUpdatedNotification("telegram://a/chat/42")) }
    }

    @Test fun `pending URIs remember which chats changed and a gap widens them to everyone`() {
        val sink = RecordingSink()
        val notifier = ResourceUpdateNotifier(sink, 0)
        notifier.enqueue(listOf("telegram://a/chats"), 0, 42)
        notifier.enqueue(listOf("telegram://a/chats"), 0, 43)
        notifier.flush(0)
        assertEquals(setOf(42L, 43L), sink.sources["telegram://a/chats"])
        notifier.enqueue(listOf("telegram://a/chats"), 1, 42)
        notifier.enqueue(listOf("telegram://a/chats"), 1, null)
        notifier.enqueue(listOf("telegram://a/chats"), 1, 44)
        notifier.flush(1)
        assertEquals(null, sink.sources["telegram://a/chats"])
    }

    @Test fun `source chats are capped and a change without a chat is not queued`() {
        val sink = RecordingSink()
        val notifier = ResourceUpdateNotifier(sink, 0)
        (1L..(ResourceUpdateNotifier.MAX_SOURCE_CHATS + 10L)).forEach { notifier.enqueue(listOf("telegram://a/chats"), 0, it) }
        notifier.onChange("a", "new", null, null)
        notifier.flush(0)
        assertEquals(ResourceUpdateNotifier.MAX_SOURCE_CHATS, sink.sources["telegram://a/chats"]!!.size)
        assertEquals(listOf("telegram://a/chats"), sink.delivered)
    }

    @Test fun `HTTP chat-list updates reach only sessions that may read a chat that changed`() {
        val registry = ResourceSubscriptionRegistry()
        val owner = TestingAuthenticationToken("owner", null, "ROLE_X")
        val scoped = TestingAuthenticationToken("scoped", null, "ROLE_X")
        registry.add("s-owner", owner, "telegram://a/chats")
        registry.add("s-scoped", scoped, "telegram://a/chats")
        val access = mockk<TelegramResourceAccess>()
        every { access.permits(any(), owner, any()) } returns true
        every { access.permits(TelegramResourceUri.Chats("a"), scoped, any()) } returns true
        every { access.permits(TelegramResourceUri.Chat("a", 42), scoped, any()) } returns true
        every { access.permits(TelegramResourceUri.Chat("a", 43), scoped, any()) } returns false
        val transport = mockk<McpStreamableServerTransportProvider>()
        every { transport.notifyClient(any(), any(), any()) } returns Mono.empty()
        val sink = HttpResourceUpdateSink(registry, access) { transport }

        sink.deliver("telegram://a/chats", setOf(43))
        verify(exactly = 1) { transport.notifyClient("s-owner", any(), any()) }
        verify(exactly = 0) { transport.notifyClient("s-scoped", any(), any()) }

        sink.deliver("telegram://a/chats", setOf(43, 42))
        verify(exactly = 1) { transport.notifyClient("s-scoped", any(), any()) }

        sink.deliver("telegram://a/chats", null) // coverage gap: not chat-specific
        verify(exactly = 2) { transport.notifyClient("s-scoped", any(), any()) }
        verify(exactly = 3) { transport.notifyClient("s-owner", any(), any()) }
    }

    @Test fun `stdio chat-list updates need a readable source chat`() {
        val access = mockk<TelegramResourceAccess>()
        every { access.permits(TelegramResourceUri.Chats("a"), null, any()) } returns true
        every { access.permits(TelegramResourceUri.Chat("a", 42), null, any()) } returns true
        every { access.permits(TelegramResourceUri.Chat("a", 43), null, any()) } returns false
        val server = mockk<io.modelcontextprotocol.server.McpSyncServer>(relaxed = true)
        val sink = StdioResourceUpdateSink(access) { server }
        sink.deliver("telegram://a/chats", setOf(43))
        verify(exactly = 0) { server.notifyResourcesUpdated(any()) }
        sink.deliver("telegram://a/chats", setOf(42))
        verify(exactly = 1) { server.notifyResourcesUpdated(McpSchema.ResourcesUpdatedNotification("telegram://a/chats")) }
    }
}
