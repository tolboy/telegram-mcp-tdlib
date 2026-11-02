package dev.telegrammcp.server.resource

import dev.telegrammcp.server.config.McpSecurityProperties
import dev.telegrammcp.server.config.ServerModeProperties
import dev.telegrammcp.server.security.AccountAccessPolicy
import dev.telegrammcp.server.service.GuardrailService
import dev.telegrammcp.server.service.ToolSurfacePolicy
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.modelcontextprotocol.server.McpSyncServer
import io.modelcontextprotocol.spec.McpSchema
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.TestingAuthenticationToken
import reactor.core.publisher.Mono

/**
 * Each resource's notifications are authorized with that resource's own backing
 * tool, on HTTP and STDIO: `chats` with `list_chats` (its source-chat check
 * included), `chat` with `conversation_bundle`, `message` with `get_messages`.
 * Adapted from the external review's reproducer.
 */
class ResourceNotificationBackingToolTest {
    private val auth = TestingAuthenticationToken("alice", null, "ROLE_X")

    /** Only [allowed] tools are on the surface; only chat 42 passes the chat allow-list. */
    private fun access(vararg allowed: String): TelegramResourceAccess {
        val accounts = mockk<AccountAccessPolicy>()
        every { accounts.selectAccount(any()) } returns "a"
        val guard = mockk<GuardrailService>()
        every { guard.isChatAllowed(any()) } answers { firstArg<Long>() == 42L }
        return TelegramResourceAccess(accounts, ToolSurfacePolicy(McpSecurityProperties(toolAllow = allowed.toList())),
            ServerModeProperties(), guard)
    }

    private fun http(access: TelegramResourceAccess, uri: String): Pair<HttpResourceUpdateSink, McpStreamableServerTransportProvider> {
        val registry = ResourceSubscriptionRegistry()
        registry.add("session", auth, uri)
        val transport = mockk<McpStreamableServerTransportProvider>()
        every { transport.notifyClient(any(), any(), any()) } returns Mono.empty()
        return HttpResourceUpdateSink(registry, access) { transport } to transport
    }

    @Test fun `HTTP chat-list notifications need list_chats only, and still a permitted source chat`() {
        val (sink, transport) = http(access("list_chats"), "telegram://a/chats")
        sink.deliver("telegram://a/chats", setOf(43))
        verify(exactly = 0) { transport.notifyClient(any(), any(), any()) }
        sink.deliver("telegram://a/chats", setOf(42))
        verify(exactly = 1) { transport.notifyClient("session", any(), any()) }
    }

    @Test fun `STDIO chat-list notifications need list_chats only, and still a permitted source chat`() {
        val server = mockk<McpSyncServer>(relaxed = true)
        val sink = StdioResourceUpdateSink(access("list_chats")) { server }
        sink.deliver("telegram://a/chats", setOf(43))
        verify(exactly = 0) { server.notifyResourcesUpdated(any()) }
        sink.deliver("telegram://a/chats", setOf(42))
        verify(exactly = 1) { server.notifyResourcesUpdated(McpSchema.ResourcesUpdatedNotification("telegram://a/chats")) }
    }

    @Test fun `chat-list notifications stop when list_chats is hidden even if the chat tools are visible`() {
        val (sink, transport) = http(access("conversation_bundle", "get_messages"), "telegram://a/chats")
        sink.deliver("telegram://a/chats", setOf(42))
        verify(exactly = 0) { transport.notifyClient(any(), any(), any()) }
    }

    @Test fun `chat and message notifications follow their own backing tools`() {
        val chat = "telegram://a/chat/42"
        val message = "telegram://a/chat/42/message/7"
        val (onlyList, listTransport) = http(access("list_chats"), chat)
        onlyList.deliver(chat)
        verify(exactly = 0) { listTransport.notifyClient(any(), any(), any()) }

        val (bundle, bundleTransport) = http(access("conversation_bundle"), chat)
        bundle.deliver(chat)
        verify(exactly = 1) { bundleTransport.notifyClient("session", any(), any()) }

        val (messages, messagesTransport) = http(access("get_messages"), message)
        messages.deliver(message)
        verify(exactly = 1) { messagesTransport.notifyClient("session", any(), any()) }

        val server = mockk<McpSyncServer>(relaxed = true)
        val stdio = StdioResourceUpdateSink(access("list_chats")) { server }
        stdio.deliver(chat)
        stdio.deliver(message)
        verify(exactly = 0) { server.notifyResourcesUpdated(any()) }
    }
}
