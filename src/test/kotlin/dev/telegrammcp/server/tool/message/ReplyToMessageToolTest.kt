package dev.telegrammcp.server.tool.message

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.exception.ConfirmationRequiredException
import dev.telegrammcp.server.model.ParseMode
import dev.telegrammcp.server.model.TelegramMessage
import dev.telegrammcp.server.service.AuditService
import dev.telegrammcp.server.service.EntityResolverService
import dev.telegrammcp.server.service.GuardrailService
import dev.telegrammcp.server.service.OperationGuardService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReplyToMessageToolTest {

    private lateinit var telegramClient: TelegramClientService
    private lateinit var entityResolver: EntityResolverService
    private lateinit var guardrailService: GuardrailService
    private lateinit var operationGuardService: OperationGuardService
    private lateinit var auditService: AuditService
    private lateinit var objectMapper: ObjectMapper
    private lateinit var tool: ReplyToMessageTool
    private lateinit var exchange: McpSyncServerExchange

    @BeforeEach
    fun setUp() {
        telegramClient = mockk()
        entityResolver = mockk()
        guardrailService = mockk(relaxed = true)
        operationGuardService = mockk(relaxed = true)
        auditService = mockk(relaxed = true)
        objectMapper = jacksonObjectMapper().findAndRegisterModules()
        exchange = mockk(relaxed = true)

        tool = ReplyToMessageTool(
            telegramClient = telegramClient,
            entityResolver = entityResolver,
            guardrailService = guardrailService,
            operationGuardService = operationGuardService,
            auditService = auditService,
            objectMapper = objectMapper,
            meterRegistry = SimpleMeterRegistry(),
        )
    }

    @Test
    fun `definition returns correct tool name`() {
        val def = tool.definition()
        assertEquals("reply_to_message", def.name())
    }

    @Test
    fun `blocks reply when operation guard rejects the call`() {
        every {
            operationGuardService.checkPermission("reply_to_message", any())
        } throws ConfirmationRequiredException("reply_to_message", "This is a destructive operation")

        val result = tool.execute(
            exchange,
            mapOf("chat_id" to 42, "message_id" to 55, "text" to "Great point!"),
        )

        assertTrue(result.isError)
        verify(exactly = 0) { telegramClient.replyToMessage(any(), any(), any(), any()) }
    }

    @Test
    fun `replies to a message with plain text`() {
        val replyMsg = TelegramMessage(
            messageId = 101, chatId = 42, chatTitle = "Test",
            senderName = "Bot", text = "Great point!", date = Instant.now(),
            replyToMessageId = 55,
        )
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { telegramClient.replyToMessage(42L, 55L, "Great point!", ParseMode.PLAIN) } returns replyMsg

        val result = tool.execute(
            exchange,
            mapOf("chat_id" to 42, "message_id" to 55, "text" to "Great point!"),
        )

        assertFalse(result.isError)
        verify { guardrailService.validateInput("Great point!") }
        verify { guardrailService.validateChatAccess(42L) }
    }

    @Test
    fun `replies with markdown parse mode`() {
        val replyMsg = TelegramMessage(
            messageId = 102, chatId = 42, chatTitle = "Test",
            senderName = "Bot", text = "*bold*", date = Instant.now(),
            replyToMessageId = 55,
        )
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { telegramClient.replyToMessage(42L, 55L, "*bold*", ParseMode.MARKDOWN) } returns replyMsg

        val result = tool.execute(
            exchange,
            mapOf("chat_id" to 42, "message_id" to 55, "text" to "*bold*", "parse_mode" to "markdown"),
        )

        assertFalse(result.isError)
    }

    @Test
    fun `accepts md as markdown alias`() {
        val replyMsg = TelegramMessage(
            messageId = 103, chatId = 42, chatTitle = "Test",
            senderName = "Bot", text = "text", date = Instant.now(),
            replyToMessageId = 55,
        )
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { telegramClient.replyToMessage(42L, 55L, "text", ParseMode.MARKDOWN) } returns replyMsg

        val result = tool.execute(
            exchange,
            mapOf("chat_id" to 42, "message_id" to 55, "text" to "text", "parse_mode" to "md"),
        )

        assertFalse(result.isError)
    }

    @Test
    fun `returns error when Telegram does not attach reply`() {
        val unlinkedMsg = TelegramMessage(
            messageId = 104, chatId = 42, chatTitle = "Test",
            senderName = "Bot", text = "text", date = Instant.now(),
            replyToMessageId = null,
        )
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { telegramClient.replyToMessage(42L, 55L, "text", ParseMode.PLAIN) } returns unlinkedMsg

        val result = tool.execute(
            exchange,
            mapOf("chat_id" to 42, "message_id" to 55, "text" to "text"),
        )

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("did not attach reply"))
    }

    @Test
    fun `returns error when chat_id is missing`() {
        val result = tool.execute(
            exchange,
            mapOf("message_id" to 55, "text" to "reply"),
        )

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("chat_id is required"))
    }

    @Test
    fun `returns error when message_id is missing`() {
        every { entityResolver.resolve(42 as Any) } returns 42L

        val result = tool.execute(
            exchange,
            mapOf("chat_id" to 42, "text" to "reply"),
        )

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("message_id is required"))
    }

    @Test
    fun `returns error when text is missing`() {
        every { entityResolver.resolve(42 as Any) } returns 42L

        val result = tool.execute(
            exchange,
            mapOf("chat_id" to 42, "message_id" to 55),
        )

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("text is required"))
    }

    @Test
    fun `returns error when text is blank`() {
        every { entityResolver.resolve(42 as Any) } returns 42L

        val result = tool.execute(
            exchange,
            mapOf("chat_id" to 42, "message_id" to 55, "text" to "  "),
        )

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("text must not be blank"))
    }

    @Test
    fun `returns error for invalid parse_mode`() {
        every { entityResolver.resolve(42 as Any) } returns 42L

        val result = tool.execute(
            exchange,
            mapOf("chat_id" to 42, "message_id" to 55, "text" to "test", "parse_mode" to "rtf"),
        )

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("parse_mode"))
    }
}

