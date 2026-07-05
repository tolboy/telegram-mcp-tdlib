package dev.telegrammcp.server.tool.message

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
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
        )
    }

    @Test
    fun `definition returns correct tool name`() {
        assertEquals("press_inline_button", tool.definition().name())
    }

    @Test
    fun `presses button by index successfully`() {
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { telegramClient.pressInlineButton(42L, 100L, 0, null) } returns "Button pressed"

        val result = tool.execute(
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

        val result = tool.execute(
            exchange,
            mapOf("chat_id" to 42, "message_id" to 100, "button_text" to "OK"),
        )

        assertFalse(result.isError)
        verify { telegramClient.pressInlineButton(42L, 100L, null, "OK") }
    }

    @Test
    fun `returns error when neither button_index nor button_text is provided`() {
        every { entityResolver.resolve(42 as Any) } returns 42L

        val result = tool.execute(
            exchange,
            mapOf("chat_id" to 42, "message_id" to 100),
        )

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("button_index") || text.contains("button_text") || text.contains("required"))
    }
}
