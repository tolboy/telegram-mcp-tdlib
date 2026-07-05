package dev.telegrammcp.server.tool.chat

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.model.ChatInfo
import dev.telegrammcp.server.model.ChatType
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.modelcontextprotocol.server.McpSyncServerExchange
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ListChatsToolTest {

    private lateinit var telegramClient: TelegramClientService
    private lateinit var objectMapper: ObjectMapper
    private lateinit var tool: ListChatsTool
    private lateinit var exchange: McpSyncServerExchange

    @BeforeEach
    fun setUp() {
        telegramClient = mockk()
        objectMapper = jacksonObjectMapper().findAndRegisterModules()
        exchange = mockk(relaxed = true)

        tool = ListChatsTool(
            telegramClient = telegramClient,
            objectMapper = objectMapper,
            meterRegistry = SimpleMeterRegistry(),
        )
    }

    @Test
    fun `lists all chats with default parameters`() {
        val chats = listOf(
            ChatInfo(chatId = 1, title = "Chat 1", type = ChatType.PRIVATE, unreadCount = 5),
            ChatInfo(chatId = 2, title = "Chat 2", type = ChatType.GROUP, unreadCount = 0),
            ChatInfo(chatId = 3, title = "Channel", type = ChatType.CHANNEL, unreadCount = 3),
        )
        every { telegramClient.getChats(50) } returns chats

        val result = tool.execute(exchange, emptyMap())

        assertFalse(result.isError)
    }

    @Test
    fun `filters by chat type`() {
        val chats = listOf(
            ChatInfo(chatId = 1, title = "Chat 1", type = ChatType.PRIVATE),
            ChatInfo(chatId = 2, title = "Group", type = ChatType.GROUP),
        )
        every { telegramClient.getChats(50) } returns chats

        val result = tool.execute(exchange, mapOf("filter" to "group"))

        assertFalse(result.isError)
        // The response should only contain the group
        val text = (result.content.first() as io.modelcontextprotocol.spec.McpSchema.TextContent).text()
        assertTrue(text.contains("Group"))
        assertFalse(text.contains("Chat 1"))
    }

    @Test
    fun `filters unread only`() {
        val chats = listOf(
            ChatInfo(chatId = 1, title = "Unread", type = ChatType.PRIVATE, unreadCount = 5),
            ChatInfo(chatId = 2, title = "Read", type = ChatType.PRIVATE, unreadCount = 0),
        )
        every { telegramClient.getChats(50) } returns chats

        val result = tool.execute(exchange, mapOf("unread_only" to true))

        assertFalse(result.isError)
        val text = (result.content.first() as io.modelcontextprotocol.spec.McpSchema.TextContent).text()
        assertTrue(text.contains("Unread"))
        assertFalse(text.contains("\"Read\""))
    }
}

