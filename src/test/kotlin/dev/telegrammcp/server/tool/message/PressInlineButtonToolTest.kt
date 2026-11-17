package dev.telegrammcp.server.tool.message

import dev.telegrammcp.server.tool.executeChecked

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.model.InlineButtonInfo
import dev.telegrammcp.server.model.STALE_INLINE_BUTTON_MESSAGE
import dev.telegrammcp.server.security.SensitiveChatBlockedException
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PressInlineButtonToolTest {

    private lateinit var telegramClient: TelegramClientService
    private lateinit var entityResolver: EntityResolverService
    private lateinit var guardrailService: GuardrailService
    private lateinit var operationGuardService: OperationGuardService
    private lateinit var auditService: AuditService
    private lateinit var objectMapper: ObjectMapper
    private lateinit var tool: PressInlineButtonTool
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

        tool = PressInlineButtonTool(
            telegramClient = telegramClient,
            entityResolver = entityResolver,
            guardrailService = guardrailService,
            operationGuardService = operationGuardService,
            auditService = auditService,
            objectMapper = objectMapper,
            meterRegistry = SimpleMeterRegistry(),
            buttonTokens = buttonTokens,
        )
    }

    private val buttonTokens = InlineButtonTokens()
    private val keyboard = listOf(
        InlineButtonInfo(row = 0, index = 0, text = "Approve", type = "callback", callbackData = "approve", position = 0, messageEditDate = 5),
        InlineButtonInfo(row = 0, index = 1, text = "Reject", type = "callback", callbackData = "reject", position = 1, messageEditDate = 5),
    )

    private fun text(result: McpSchema.CallToolResult) = (result.content.first() as McpSchema.TextContent).text()

    @Test
    fun `definition returns correct tool name`() {
        assertEquals("press_inline_button", tool.definition().name())
    }

    @Test
    fun `presses button by index successfully`() {
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { telegramClient.pressInlineButton(42L, 100L, 0, null) } returns "Button pressed"

        val result = tool.executeChecked(
            exchange,
            mapOf("chat_id" to 42, "message_id" to 100, "button_index" to 0),
        )

        assertFalse(result.isError)
        verify { telegramClient.pressInlineButton(42L, 100L, 0, null) }
    }

    @Test
    fun `presses button by text successfully`() {
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { telegramClient.pressInlineButton(42L, 100L, null, "OK") } returns "Done"

        val result = tool.executeChecked(
            exchange,
            mapOf("chat_id" to 42, "message_id" to 100, "button_text" to "OK"),
        )

        assertFalse(result.isError)
        verify { telegramClient.pressInlineButton(42L, 100L, null, "OK") }
    }

    @Test
    fun `returns error when neither button_index nor button_text is provided`() {
        every { entityResolver.resolve(42 as Any) } returns 42L

        val result = tool.executeChecked(
            exchange,
            mapOf("chat_id" to 42, "message_id" to 100),
        )

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("button_index") || text.contains("button_text") || text.contains("required"))
    }

    @Test
    fun `deprecated selectors still work and return a deprecation warning`() {
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { telegramClient.pressInlineButton(42L, 100L, 1, null) } returns "ok"

        val result = tool.executeChecked(exchange, mapOf("chat_id" to 42, "message_id" to 100, "button_index" to 1))

        assertFalse(result.isError)
        assertTrue(PressInlineButtonTool.DEPRECATED_SELECTOR_WARNING in text(result), text(result))
        val schema = objectMapper.writeValueAsString(tool.definition().inputSchema())
        assertTrue("button_token" in schema && "\"deprecated\":true" in schema, schema)
    }

    @Test
    fun `a listed token presses exactly that button`() {
        val token = buttonTokens.sign(42, 100, keyboard)[1].token!!
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { telegramClient.listInlineButtons(42L, 100L) } returns keyboard
        every { telegramClient.pressInlineButton(42L, 100L, 1, null, keyboard[1]) } returns "Rejected"

        val result = tool.executeChecked(exchange, mapOf("chat_id" to 42, "message_id" to 100, "button_token" to token))

        assertFalse(result.isError, text(result))
        val body = objectMapper.readTree(text(result))
        assertEquals("Rejected", body["answer"].asText())
        assertEquals("Reject", body["button_text"].asText())
        assertTrue(body["warning"].isNull)
        verify(exactly = 1) { telegramClient.pressInlineButton(42L, 100L, 1, null, keyboard[1]) }
    }

    @Test
    fun `a token is refused without pressing when the bot edited the keyboard`() {
        val token = buttonTokens.sign(42, 100, keyboard)[0].token!!
        every { entityResolver.resolve(42 as Any) } returns 42L
        // The bot swapped the buttons' actions and edited the message.
        every { telegramClient.listInlineButtons(42L, 100L) } returns listOf(
            keyboard[0].copy(callbackData = "reject", messageEditDate = 6),
            keyboard[1].copy(callbackData = "approve", messageEditDate = 6),
        )

        val result = tool.executeChecked(exchange, mapOf("chat_id" to 42, "message_id" to 100, "button_token" to token))

        assertTrue(result.isError)
        assertTrue("list_inline_buttons again" in text(result), text(result))
        verify(exactly = 0) { telegramClient.pressInlineButton(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a token for another message is refused`() {
        val token = buttonTokens.sign(42, 99, keyboard)[0].token!!
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { telegramClient.listInlineButtons(42L, 100L) } returns keyboard

        val result = tool.executeChecked(exchange, mapOf("chat_id" to 42, "message_id" to 100, "button_token" to token))

        assertTrue(result.isError)
        assertTrue(STALE_INLINE_BUTTON_MESSAGE.take(40) in text(result), text(result))
        verify(exactly = 0) { telegramClient.pressInlineButton(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `token with a deprecated selector, or a malformed token, is rejected before any read`() {
        every { entityResolver.resolve(42 as Any) } returns 42L
        val token = buttonTokens.sign(42, 100, keyboard)[0].token!!
        listOf(
            mapOf("chat_id" to 42, "message_id" to 100, "button_token" to token, "button_index" to 0),
            mapOf("chat_id" to 42, "message_id" to 100, "button_token" to token, "button_text" to "Approve"),
            mapOf("chat_id" to 42, "message_id" to 100, "button_token" to "not-a-token"),
            mapOf("chat_id" to 42, "message_id" to 100, "button_token" to "ibt1." + "A".repeat(200)),
        ).forEach { args ->
            assertTrue(tool.executeChecked(exchange, args).isError, "$args")
        }
        verify(exactly = 0) { telegramClient.listInlineButtons(any(), any()) }
    }

    @Test
    fun `chat access is checked before the keyboard is read`() {
        every { entityResolver.resolve(777000 as Any) } returns 777000L
        every { guardrailService.validateChatAccess(777000L) } throws SensitiveChatBlockedException(777000L)
        val token = buttonTokens.sign(777000, 100, keyboard)[0].token!!

        val result = tool.executeChecked(exchange, mapOf("chat_id" to 777000, "message_id" to 100, "button_token" to token))

        assertTrue(result.isError)
        verify(exactly = 0) { telegramClient.listInlineButtons(any(), any()) }
    }
}
