package dev.telegrammcp.server.resource

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.client.TelegramAccountRegistry
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.model.ChatInfo
import dev.telegrammcp.server.model.ChatType
import dev.telegrammcp.server.model.TelegramMessage
import dev.telegrammcp.server.security.PermissionDocument
import dev.telegrammcp.server.security.PermissionGrant
import dev.telegrammcp.server.service.ChangeJournal
import io.mockk.every
import io.mockk.mockk
import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport
import io.modelcontextprotocol.spec.McpError
import io.modelcontextprotocol.spec.McpSchema
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.http.HttpRequest
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real servlet transport, SDK client and change journal; Telegram is a mock. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [
    "mcp.resources.enabled=true",
    "mcp.resources.debounce=50ms",
    "mcp.security.clients[0].id=scoped", "mcp.security.clients[0].api-key=scoped-key",
    "mcp.security.clients[0].allowed-accounts[0]=personal",
    "mcp.security.clients[1].id=owner", "mcp.security.clients[1].api-key=owner-key",
    "mcp.security.clients[1].allowed-accounts[0]=personal",
])
@ActiveProfiles("test")
class McpResourcesIntegrationTest {
    @Autowired lateinit var environment: Environment
    @Autowired lateinit var accounts: TelegramAccountRegistry
    @Autowired lateinit var journal: ChangeJournal
    private val mapper = jacksonObjectMapper()
    private val baseUrl get() = "http://127.0.0.1:${environment.getProperty("local.server.port")}"

    private fun message(chatId: Long, id: Long) =
        TelegramMessage(id, chatId, "Chat $chatId", "Alice", text = "text $id", date = Instant.EPOCH)

    @BeforeEach fun setUp() {
        if (!accounts.has("personal")) accounts.register(TelegramAccountRegistry.AccountHandle("personal", telegram))
        every { telegram.getHistory(any(), any(), any(), any()) } answers {
            val chatId = firstArg<Long>()
            listOf(8L, 7L, 6L).map { message(chatId, it) }
        }
        every { telegram.getChats(any()) } returns listOf(
            ChatInfo(42L, "Chat 42", ChatType.GROUP),
            ChatInfo(43L, "Chat 43", ChatType.GROUP),
        )
    }

    private fun client(key: String, updates: LinkedBlockingQueue<String>? = null): McpSyncClient =
        McpClient.sync(HttpClientStreamableHttpTransport.builder(baseUrl)
            .requestBuilder(HttpRequest.newBuilder().header("Authorization", "Bearer $key")).build())
            .requestTimeout(Duration.ofSeconds(10))
            .resourcesUpdateConsumer { contents -> contents.forEach { updates?.add(it.uri()) } } // the SDK re-reads the resource on notification
            .build()

    private fun text(result: McpSchema.ReadResourceResult) = (result.contents().single() as McpSchema.TextResourceContents)

    @Test fun `resources are listed, readable within the grant, and indistinguishable outside it`() {
        val scoped = client("scoped-key")
        val owner = client("owner-key")
        try {
            owner.initialize()
            val init = scoped.initialize()
            assertEquals(true, init.capabilities().resources().subscribe())
            assertEquals(false, init.capabilities().resources().listChanged())
            assertTrue(scoped.listResources().resources().any { it.uri() == "telegram://default/chats" })
            assertEquals(setOf(TelegramResourceUri.CHATS_TEMPLATE, TelegramResourceUri.CHAT_TEMPLATE, TelegramResourceUri.MESSAGE_TEMPLATE),
                scoped.listResourceTemplates().resourceTemplates().map { it.uriTemplate() }.toSet())

            val chat = text(scoped.readResource(McpSchema.ReadResourceRequest("telegram://personal/chat/42")))
            assertEquals("application/json", chat.mimeType())
            assertEquals(true, chat.meta()["io.github.tolboy/untrusted-content"])
            val bundle = mapper.readTree(chat.text())
            assertEquals(listOf(8, 7, 6), bundle.path("data").path("messages").map { it.path("messageId").asInt() })

            val single = mapper.readTree(text(scoped.readResource(McpSchema.ReadResourceRequest("telegram://personal/chat/42/message/7"))).text())
            assertEquals(7, single.path("data").path("messageId").asInt())
            assertEquals("text 7", single.path("data").path("text").asText())

            val chats = mapper.readTree(text(owner.readResource(McpSchema.ReadResourceRequest("telegram://personal/chats"))).text())
            assertEquals(listOf(42, 43), chats.path("data").map { it.path("chatId").asInt() })
            val scopedChats = mapper.readTree(text(scoped.readResource(McpSchema.ReadResourceRequest("telegram://personal/chats"))).text())
            assertEquals(listOf(42), scopedChats.path("data").map { it.path("chatId").asInt() }, "chat-scoped grants filter the chat list")

            val denied = listOf("telegram://personal/chat/43", "telegram://personal/chat/43/message/7",
                "telegram://default/chats", "telegram://personal/chat/042").map { uri ->
                assertThrows<McpError> { scoped.readResource(McpSchema.ReadResourceRequest(uri)) }.jsonRpcError
            }
            assertEquals(setOf(McpSchema.ErrorCodes.RESOURCE_NOT_FOUND), denied.map { it.code() }.toSet())
            assertEquals(1, denied.map { it.message() }.toSet().size)
        } finally { scoped.closeGracefully(); owner.closeGracefully() }
    }

    @Test fun `subscriptions are authorized and notifications reach only permitted sessions`() {
        val scopedUpdates = LinkedBlockingQueue<String>()
        val ownerUpdates = LinkedBlockingQueue<String>()
        val scoped = client("scoped-key", scopedUpdates)
        val owner = client("owner-key", ownerUpdates)
        try {
            scoped.initialize()
            owner.initialize()
            val denied = assertThrows<McpError> { scoped.subscribeResource(McpSchema.SubscribeRequest("telegram://personal/chat/43")) }
            assertEquals(McpSchema.ErrorCodes.RESOURCE_NOT_FOUND, denied.jsonRpcError.code())
            scoped.subscribeResource(McpSchema.SubscribeRequest("telegram://personal/chat/42"))
            owner.subscribeResource(McpSchema.SubscribeRequest("telegram://personal/chat/43"))
            owner.subscribeResource(McpSchema.SubscribeRequest("telegram://personal/chat/42"))
            Thread.sleep(300) // let both GET streams attach before the first event

            repeat(5) { journal.append("personal", "new", 42, 100L + it) }
            journal.append("personal", "edited", 43, 5)
            assertEquals("telegram://personal/chat/42", scopedUpdates.poll(10, TimeUnit.SECONDS))
            val ownerSeen = setOf(ownerUpdates.poll(10, TimeUnit.SECONDS), ownerUpdates.poll(10, TimeUnit.SECONDS))
            assertEquals(setOf("telegram://personal/chat/42", "telegram://personal/chat/43"), ownerSeen)
            assertNull(scopedUpdates.poll(500, TimeUnit.MILLISECONDS), "bursts coalesce and foreign chats stay silent")

            scoped.unsubscribeResource(McpSchema.UnsubscribeRequest("telegram://personal/chat/42"))
            journal.append("personal", "deleted", 42, 100)
            assertEquals("telegram://personal/chat/42", ownerUpdates.poll(10, TimeUnit.SECONDS))
            assertNull(scopedUpdates.poll(500, TimeUnit.MILLISECONDS))
            assertFalse(ownerUpdates.any { it.contains("text") })
        } finally { scoped.closeGracefully(); owner.closeGracefully() }
    }

    @Test fun `chat-list updates do not reveal activity in chats outside the grant`() {
        val scopedUpdates = LinkedBlockingQueue<String>()
        val ownerUpdates = LinkedBlockingQueue<String>()
        val scoped = client("scoped-key", scopedUpdates)
        val owner = client("owner-key", ownerUpdates)
        try {
            scoped.initialize()
            owner.initialize()
            scoped.subscribeResource(McpSchema.SubscribeRequest("telegram://personal/chats"))
            owner.subscribeResource(McpSchema.SubscribeRequest("telegram://personal/chats"))
            Thread.sleep(300) // let both GET streams attach before the first event

            journal.append("personal", "new", 43, 900)
            assertEquals("telegram://personal/chats", ownerUpdates.poll(10, TimeUnit.SECONDS))
            assertNull(scopedUpdates.poll(500, TimeUnit.MILLISECONDS), "activity in chat 43 stays invisible to the scoped client")

            journal.append("personal", "new", 42, 901)
            assertEquals("telegram://personal/chats", scopedUpdates.poll(10, TimeUnit.SECONDS))
            assertEquals("telegram://personal/chats", ownerUpdates.poll(10, TimeUnit.SECONDS))
        } finally { scoped.closeGracefully(); owner.closeGracefully() }
    }

    @Test fun `subscribe on an unknown session keeps the session filter's 404`() {
        val response = java.net.http.HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(java.net.URI.create("$baseUrl/mcp"))
                .header("Authorization", "Bearer owner-key")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Mcp-Session-Id", "unknown-session")
                .POST(HttpRequest.BodyPublishers.ofString(
                    """{"jsonrpc":"2.0","id":1,"method":"resources/subscribe","params":{"uri":"telegram://personal/chat/42"}}""",
                )).build(),
            java.net.http.HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(404, response.statusCode())
        assertFalse(response.body().contains("Resource"), "the subscription filter must not answer for unknown sessions")
    }

    companion object {
        private val telegram = mockk<TelegramClientService>(relaxed = true)
        private val permissions = Files.createTempFile("mcp-resource-permissions-", ".json").also {
            Files.writeString(it, jacksonObjectMapper().writeValueAsString(PermissionDocument(1, listOf(
                PermissionGrant("scoped", "personal", setOf("read"), setOf(42L)),
                PermissionGrant("owner", "personal", setOf("read"), null),
            ))))
            it.toFile().deleteOnExit()
        }
        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) { registry.add("mcp.permissions-file") { permissions.toString() } }
    }
}
