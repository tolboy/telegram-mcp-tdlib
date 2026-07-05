package dev.telegrammcp.server.tool.chat

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.model.ChatInfo
import dev.telegrammcp.server.model.ChatType
import dev.telegrammcp.server.service.EntityResolverService
import dev.telegrammcp.server.service.GuardrailService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GetChatToolTest {

    private lateinit var telegramClient: TelegramClientService
    private lateinit var entityResolver: EntityResolverService
    private lateinit var guardrailService: GuardrailService
    private lateinit var objectMapper: ObjectMapper
    private lateinit var tool: GetChatTool
    private lateinit var exchange: McpSyncServerExchange

    @BeforeEach
    fun setUp() {
        telegramClient = mockk()
        entityResolver = mockk()
        guardrailService = mockk(relaxed = true)
        objectMapper = jacksonObjectMapper().findAndRegisterModules()
        exchange = mockk(relaxed = true)

        tool = GetChatTool(
            telegramClient = telegramClient,
            entityResolver = entityResolver,
            guardrailService = guardrailService,
            objectMapper = objectMapper,
            meterRegistry = SimpleMeterRegistry(),
        )
    }

    @Test
    fun `definition returns correct tool name`() {
        val def = tool.definition()
        assertEquals("get_chat", def.name())
    }

    @Test
    fun `returns chat info on success`() {
        val chatInfo = ChatInfo(
            chatId = 42, title = "Kotlin Devs", type = ChatType.SUPERGROUP,
            memberCount = 150, description = "Kotlin discussion group",
        )
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { telegramClient.getChat(42L) } returns chatInfo

        val result = tool.execute(exchange, mapOf("chat_id" to 42))

        assertFalse(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("Kotlin Devs"))
        assertTrue(text.contains("SUPERGROUP"))
        verify { guardrailService.validateChatAccess(42L) }
    }

    @Test
    fun `resolves username to chat id`() {
        val chatInfo = ChatInfo(
            chatId = 99, title = "My Channel", type = ChatType.CHANNEL,
        )
        every { entityResolver.resolve("@mychannel" as Any) } returns 99L
        every { telegramClient.getChat(99L) } returns chatInfo

        val result = tool.execute(exchange, mapOf("chat_id" to "@mychannel"))

        assertFalse(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("My Channel"))
    }

    @Test
    fun `returns error when chat_id is missing`() {
        val result = tool.execute(exchange, emptyMap())

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("chat_id is required"))
    }

    @Test
    fun `returns error when telegram client throws`() {
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { telegramClient.getChat(42L) } throws RuntimeException("Chat not found")

        val result = tool.execute(exchange, mapOf("chat_id" to 42))

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("Chat not found"))
    }
}

