package dev.telegrammcp.server.tool.research

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.model.TelegramMessage
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExportChatHistoryToolTest {

    private lateinit var telegramClient: TelegramClientService
    private lateinit var entityResolver: EntityResolverService
    private lateinit var guardrailService: GuardrailService
    private lateinit var objectMapper: ObjectMapper
    private lateinit var tool: ExportChatHistoryTool
    private lateinit var exchange: McpSyncServerExchange

    @BeforeEach
    fun setUp() {
        telegramClient = mockk()
        entityResolver = mockk()
        guardrailService = mockk(relaxed = true)
        objectMapper = jacksonObjectMapper().findAndRegisterModules()
        exchange = mockk(relaxed = true)

        tool = ExportChatHistoryTool(
            telegramClient = telegramClient,
            entityResolver = entityResolver,
            guardrailService = guardrailService,
            objectMapper = objectMapper,
            meterRegistry = SimpleMeterRegistry(),
        )
    }

    @Test
    fun `definition returns tool name`() {
        assertEquals("export_chat_history", tool.definition().name())
    }

    @Test
    fun `exports history and filters by date`() {
        val older = msg(1, "old", "2026-01-01T00:00:00Z")
        val fresh = msg(2, "fresh", "2026-05-01T23:15:00Z")
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { telegramClient.getHistory(42L, 0L, 0, 100) } returns listOf(fresh, older)

        val result = tool.execute(
            exchange,
            mapOf(
                "chat_id" to 42,
                "since" to "2026-05-01",
                "until" to "2026-05-01",
                "limit" to 20,
            ),
        )

        assertFalse(result.isError)
        val payload = payload(result)
        assertEquals(1, payload["total"])
        verify { guardrailService.validateChatAccess(42L) }
    }

    @Test
    fun `supplements service-only history with query term search`() {
        val service = msg(10, "[MessageChatAddMembers]", "2026-05-16T00:00:00Z")
        val lead = msg(9, "Товарищи у вас ненужных соток колес нету?", "2026-05-01T00:00:00Z")
        every { entityResolver.resolve(42L as Any) } returns 42L
        every { telegramClient.getHistory(42L, 0L, 0, 100) } returns listOf(service)
        every { telegramClient.searchMessages(42L, "сотки колеса", 0L, 100) } returns listOf(lead)

        val result = tool.execute(
            exchange,
            mapOf(
                "chat_id" to 42L,
                "since" to "2026-04-01",
                "query_terms" to listOf("сотки колеса"),
                "limit" to 20,
            ),
        )

        assertFalse(result.isError)
        val payload = payload(result)
        assertEquals(2, payload["total"])
        assertEquals(1, payload["search_messages"])
        verify { guardrailService.validateInput("сотки колеса") }
    }

    @Test
    fun `rejects invalid date window`() {
        val result = tool.execute(
            exchange,
            mapOf("chat_id" to 42L, "since" to "2026-05-02", "until" to "2026-05-01"),
        )

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("since must be before until"))
    }

    private fun msg(id: Long, text: String, iso: String): TelegramMessage =
        TelegramMessage(
            messageId = id,
            chatId = 42L,
            chatTitle = "Test",
            senderName = "Alice",
            text = text,
            date = Instant.parse(iso),
        )

    private fun payload(result: McpSchema.CallToolResult): Map<*, *> {
        val text = (result.content.first() as McpSchema.TextContent).text()
        return objectMapper.readValue(text, Map::class.java)
    }
}
