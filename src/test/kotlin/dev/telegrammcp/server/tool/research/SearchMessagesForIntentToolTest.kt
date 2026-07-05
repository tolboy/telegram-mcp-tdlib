package dev.telegrammcp.server.tool.research

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

        val result = tool.execute(
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

        val result = tool.execute(
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

        val result = timeoutTool.execute(
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
