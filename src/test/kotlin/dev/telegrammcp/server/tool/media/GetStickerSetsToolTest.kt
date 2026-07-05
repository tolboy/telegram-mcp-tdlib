package dev.telegrammcp.server.tool.media

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.model.StickerSetInfo
import dev.telegrammcp.server.service.AuditService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.modelcontextprotocol.server.McpSyncServerExchange
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class GetStickerSetsToolTest {

    private lateinit var telegramClient: TelegramClientService
    private lateinit var auditService: AuditService
    private lateinit var objectMapper: ObjectMapper
    private lateinit var tool: GetStickerSetsTool
    private lateinit var exchange: McpSyncServerExchange

    @BeforeEach
    fun setUp() {
        telegramClient = mockk()
        auditService = mockk(relaxed = true)
        objectMapper = jacksonObjectMapper().findAndRegisterModules()
        exchange = mockk(relaxed = true)

        tool = GetStickerSetsTool(
            telegramClient = telegramClient,
            auditService = auditService,
            objectMapper = objectMapper,
            meterRegistry = SimpleMeterRegistry(),
        )
    }

    @Test
    fun `definition returns correct tool name`() {
        assertEquals("get_sticker_sets", tool.definition().name())
    }

    @Test
    fun `returns sticker sets successfully`() {
        val sets = listOf(
            StickerSetInfo(1L, "Animals", "animals", 20, isOfficial = false, isArchived = false, isOwned = false),
        )
        every { telegramClient.getInstalledStickerSets(50) } returns sets

        val result = tool.execute(exchange, emptyMap())

        assertFalse(result.isError)
    }

    @Test
    fun `respects custom limit`() {
        every { telegramClient.getInstalledStickerSets(10) } returns emptyList()

        val result = tool.execute(exchange, mapOf("limit" to 10))

        assertFalse(result.isError)
    }
}
