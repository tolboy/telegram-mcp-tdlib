package dev.telegrammcp.server.tool.research

import dev.telegrammcp.server.tool.executeChecked
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.config.PublicSearchProperties
import dev.telegrammcp.server.model.TelegramMessage
import dev.telegrammcp.server.service.AuditService
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
import java.time.Instant
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class SearchMessagesForIntentToolTest {

    private lateinit var telegramClient: TelegramClientService
    private lateinit var entityResolver: EntityResolverService
    private lateinit var guardrailService: GuardrailService
    private lateinit var auditService: AuditService
    private lateinit var objectMapper: ObjectMapper
    private lateinit var exchange: McpSyncServerExchange
    private lateinit var tool: SearchMessagesForIntentTool

    @BeforeEach
    fun setUp() {
        telegramClient = mockk()
        entityResolver = mockk()
        guardrailService = mockk(relaxed = true)
        auditService = mockk(relaxed = true)
        objectMapper = jacksonObjectMapper().findAndRegisterModules()
        exchange = mockk(relaxed = true)
        tool = createTool()
    }

    @Test
    fun `searches caller supplied multilingual variants and returns matching hits`() {
        val message = TelegramMessage(
            messageId = 1,
            chatId = 42,
            chatTitle = "test-channel",
            senderName = "User",
            text = "We are hiring a Kotlin developer",
            date = Instant.now(),
        )
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { telegramClient.searchMessages(42L, any(), 0L, 10) } returns emptyList()
        every { telegramClient.searchMessages(42L, "hiring", 0L, 10) } returns listOf(message)

        val result = tool.executeChecked(
            exchange,
            mapOf(
                "chats" to listOf(42),
                "query" to "looking for a developer",
                "query_variants" to listOf("hiring", "Ищу разработчика"),
                "limit_per_chat" to 10,
            ),
        )

        assertFalse(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("search_queries"))
        assertTrue(text.contains("hiring"))
        assertTrue(text.contains("Ищу разработчика"))
        assertTrue(text.contains("Kotlin developer"))
        verify { guardrailService.validateInput("looking for a developer") }
        verify { guardrailService.validateInput("hiring") }
        verify { guardrailService.validateInput("Ищу разработчика") }
        verify { guardrailService.validateChatAccess(42L) }
        verify { telegramClient.searchMessages(42L, "looking for a developer", 0L, 10) }
        verify { telegramClient.searchMessages(42L, "hiring", 0L, 10) }
    }

    @Test
    fun `deduplicates caller supplied variants without changing their language`() {
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { telegramClient.searchMessages(42L, any(), 0L, 10) } returns emptyList()

        val result = tool.executeChecked(
            exchange,
            mapOf(
                "chats" to listOf(42),
                "query" to "Hello",
                "query_variants" to listOf("hello", "Hola", "Привет", "hola"),
            ),
        )

        assertFalse(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("Hello"))
        assertTrue(text.contains("Hola"))
        assertTrue(text.contains("Привет"))
        verify(exactly = 3) { telegramClient.searchMessages(42L, any(), 0L, 10) }
    }

    @Test
    fun `timeout response preserves completed chat results`() {
        val fastMessage = TelegramMessage(
            messageId = 10,
            chatId = 42,
            chatTitle = "fast-channel",
            senderName = "User",
            text = "looking for a developer",
            date = Instant.now(),
        )
        val timeoutTool = createTool(
            PublicSearchProperties(
                limits = PublicSearchProperties.LimitsProps(maxMessagesPerChat = 10),
                fanout = PublicSearchProperties.FanoutProps(
                    maxConcurrentChats = 2,
                    maxConcurrentQueriesPerChat = 1,
                    toolCallTimeoutMs = 100,
                ),
            ),
        )
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { entityResolver.resolve(43 as Any) } returns 43L
        every { telegramClient.searchMessages(42L, any(), 0L, 10) } returns emptyList()
        every { telegramClient.searchMessages(42L, "looking for a developer", 0L, 10) } returns listOf(fastMessage)
        every { telegramClient.searchMessages(43L, any(), 0L, 10) } answers {
            Thread.sleep(500)
            emptyList()
        }

        val result = timeoutTool.executeChecked(
            exchange,
            mapOf(
                "chats" to listOf(42, 43),
                "query" to "looking for a developer",
                "limit_per_chat" to 10,
            ),
        )

        assertFalse(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("\"timed_out\""))
        assertTrue(text.contains("fast-channel"))
        assertTrue(text.contains("looking for a developer"))
        val data = objectMapper.readTree(text)
        assertFalse(data["complete"].asBoolean())
        assertEquals("43", data["incomplete_chats"][0]["chat"].asText())
    }

    @Test
    fun `failed variant is visible while successful hits survive`() {
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { telegramClient.searchMessages(42L, "primary", 0L, 10) } throws IllegalStateException("upstream unavailable")
        every { telegramClient.searchMessages(42L, "variant", 0L, 10) } returns listOf(
            TelegramMessage(messageId = 1, chatId = 42, chatTitle = "test", senderName = "User", text = "match", date = Instant.now()),
        )

        val result = tool.executeChecked(exchange, mapOf(
            "chats" to listOf(42), "query" to "primary", "query_variants" to listOf("variant"),
        ))
        val data = objectMapper.readTree((result.content.first() as McpSchema.TextContent).text())

        assertFalse(result.isError)
        assertFalse(data["complete"].asBoolean())
        val chat = data["chats"][0]
        assertEquals("match", chat["messages"][0]["text"].asText())
        assertEquals("primary", chat["failed_queries"][0]["query"].asText())
        assertEquals("upstream unavailable", chat["failed_queries"][0]["error"].asText())
        assertEquals("query_failed", chat["partial_reasons"][0].asText())
    }

    @Test
    fun `all failed queries differ from a successful empty search`() {
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { telegramClient.searchMessages(42L, "query", 0L, 10) } throws IllegalStateException("failed")
        val args = mapOf("chats" to listOf(42), "query" to "query")

        val failed = tool.executeChecked(exchange, args)
        val failedData = objectMapper.readTree((failed.content.first() as McpSchema.TextContent).text())
        assertFalse(failedData["complete"].asBoolean())
        assertEquals(1, failedData["chats"][0]["failed_queries"].size())

        every { telegramClient.searchMessages(42L, "query", 0L, 10) } returns emptyList()
        val empty = tool.executeChecked(exchange, args)
        val emptyData = objectMapper.readTree((empty.content.first() as McpSchema.TextContent).text())
        assertTrue(emptyData["complete"].asBoolean())
        assertEquals(0, emptyData["chats"][0]["failed_queries"].size())
    }

    @Test
    fun `capped query results remain incomplete`() {
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { telegramClient.searchMessages(42L, "query", 0L, 1) } returns listOf(
            TelegramMessage(messageId = 1, chatId = 42, chatTitle = "test", senderName = "User", text = "match", date = Instant.now()),
        )
        val result = tool.executeChecked(exchange, mapOf("chats" to listOf(42), "query" to "query", "limit_per_chat" to 1))
        val data = objectMapper.readTree((result.content.first() as McpSchema.TextContent).text())
        assertFalse(data["complete"].asBoolean())
        assertTrue(data["chats"][0]["truncated"].asBoolean())
        assertEquals("result_limit", data["chats"][0]["partial_reasons"][0].asText())
    }

    @Test
    fun `denied chat is not silently omitted or searched`() {
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { guardrailService.validateChatAccess(42L) } throws
            dev.telegrammcp.server.exception.ChatNotAllowedException(42L)
        val result = tool.executeChecked(exchange, mapOf("chats" to listOf(42), "query" to "query"))
        val data = objectMapper.readTree((result.content.first() as McpSchema.TextContent).text())
        assertFalse(data["complete"].asBoolean())
        assertEquals("chat_failed", data["chats"][0]["partial_reasons"][0].asText())
        verify(exactly = 0) { telegramClient.searchMessages(any(), any(), any(), any()) }
    }

    private fun createTool(
        publicSearchProps: PublicSearchProperties = PublicSearchProperties(
            limits = PublicSearchProperties.LimitsProps(maxMessagesPerChat = 10),
        ),
    ): SearchMessagesForIntentTool = SearchMessagesForIntentTool(
        telegramClient = telegramClient,
        entityResolver = entityResolver,
        publicSearchProps = publicSearchProps,
        guardrailService = guardrailService,
        auditService = auditService,
        objectMapper = objectMapper,
        meterRegistry = SimpleMeterRegistry(),
    )
}
