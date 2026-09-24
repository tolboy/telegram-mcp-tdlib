package dev.telegrammcp.server.tool.research

import dev.telegrammcp.server.tool.executeChecked
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
        every { telegramClient.getHistory(any(), any(), any(), any()) } returns emptyList()
        every { telegramClient.searchMessages(any(), any(), any(), any()) } returns emptyList()

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

        val result = tool.executeChecked(
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
        assertEquals(true, payload["complete"])
        assertEquals(false, payload["truncated"])
        verify { guardrailService.validateChatAccess(42L) }
    }

    @Test
    fun `supplements service-only history with query term search`() {
        val service = msg(10, "[MessageChatAddMembers]", "2026-05-16T00:00:00Z")
        val lead = msg(9, "Товарищи у вас ненужных соток колес нету?", "2026-05-01T00:00:00Z")
        every { entityResolver.resolve(42L as Any) } returns 42L
        every { telegramClient.getHistory(42L, 0L, 0, 100) } returns listOf(service)
        every { telegramClient.searchMessages(42L, "сотки колеса", 0L, 100) } returns listOf(lead)

        val result = tool.executeChecked(
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
        val result = tool.executeChecked(
            exchange,
            mapOf("chat_id" to 42L, "since" to "2026-05-02", "until" to "2026-05-01"),
        )

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("since must be before until"))
    }

    @Test
    fun `exact result limit does not claim history is exhausted`() {
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { telegramClient.getHistory(42L, 0L, 0, 100) } returns
            listOf(msg(2, "two", "2026-05-01T00:00:00Z"), msg(1, "one", "2026-05-01T00:00:00Z"))

        val data = payload(tool.executeChecked(exchange, mapOf("chat_id" to 42, "limit" to 2)))

        assertEquals(2, data["total"])
        assertEquals(false, data["complete"])
        assertEquals(true, data["truncated"])
        assertEquals(listOf("result_limit"), data["partial_reasons"])
        verify(exactly = 1) { telegramClient.getHistory(any(), any(), any(), any()) }
    }

    @Test
    fun `continues after short pages until empty page`() {
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { telegramClient.getHistory(42L, 0L, 0, 100) } returns
            listOf(msg(2, "two", "2026-05-01T00:00:00Z"))
        every { telegramClient.getHistory(42L, 2L, 0, 100) } returns
            listOf(msg(1, "one", "2026-05-01T00:00:00Z"))

        val data = payload(tool.executeChecked(exchange, mapOf("chat_id" to 42)))

        assertEquals(2, data["total"])
        assertEquals(2, data["scanned_count"])
        assertEquals(true, data["complete"])
        verify { telegramClient.getHistory(42L, 1L, 0, 100) }
    }

    @Test
    fun `page budget outside requested date range remains incomplete`() {
        every { entityResolver.resolve(42 as Any) } returns 42L
        var id = 100L
        every { telegramClient.getHistory(42L, any(), 0, 100) } answers {
            listOf(msg(id--, "newer", "2026-05-01T00:00:00Z"))
        }

        val data = payload(tool.executeChecked(exchange, mapOf("chat_id" to 42, "until" to "2026-01-01")))

        assertEquals(0, data["total"])
        assertEquals(false, data["complete"])
        assertEquals(listOf("page_limit"), data["partial_reasons"])
        assertEquals(12, data["scanned_count"])
        verify(exactly = 12) { telegramClient.getHistory(any(), any(), any(), any()) }
    }

    @Test
    fun `repeated page reports stalled cursor instead of completion`() {
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { telegramClient.getHistory(42L, any(), 0, 100) } returns
            listOf(msg(2, "two", "2026-05-01T00:00:00Z"))

        val data = payload(tool.executeChecked(exchange, mapOf("chat_id" to 42)))

        assertEquals(1, data["total"])
        assertEquals(listOf("cursor_stalled"), data["partial_reasons"])
        assertEquals(false, data["complete"])
        verify(exactly = 2) { telegramClient.getHistory(any(), any(), any(), any()) }
    }

    @Test
    fun `search-only completeness is scoped to requested sources`() {
        every { entityResolver.resolve(42 as Any) } returns 42L

        val data = payload(tool.executeChecked(exchange, mapOf(
            "chat_id" to 42, "include_history" to false, "query_terms" to listOf("missing"),
        )))

        assertEquals(true, data["complete"])
        assertEquals("requested_sources", data["completion_scope"])
        verify(exactly = 0) { telegramClient.getHistory(any(), any(), any(), any()) }
    }

    @Test
    fun `search budget and unvisited sources remain incomplete`() {
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { telegramClient.searchMessages(42L, "first", 0L, 100) } returns
            listOf(msg(2, "two", "2026-05-01T00:00:00Z"))

        val data = payload(tool.executeChecked(exchange, mapOf(
            "chat_id" to 42, "limit" to 1, "query_terms" to listOf("first", "second"),
        )))

        assertEquals(false, data["complete"])
        assertEquals(listOf("result_limit"), data["partial_reasons"])
        verify(exactly = 0) { telegramClient.searchMessages(42L, "second", any(), any()) }
        verify(exactly = 0) { telegramClient.getHistory(any(), any(), any(), any()) }
    }

    @Test
    fun `rejects export without any source`() {
        every { entityResolver.resolve(42 as Any) } returns 42L
        val result = tool.executeChecked(exchange, mapOf("chat_id" to 42, "include_history" to false))
        assertTrue(result.isError)
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
