package dev.telegrammcp.server.tool.media

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.model.DownloadResult
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadMediaToolTest {

    private lateinit var telegramClient: TelegramClientService
    private lateinit var entityResolver: EntityResolverService
    private lateinit var guardrailService: GuardrailService
    private lateinit var auditService: AuditService
    private lateinit var objectMapper: ObjectMapper
    private lateinit var tool: DownloadMediaTool
    private lateinit var exchange: McpSyncServerExchange

    @BeforeEach
    fun setUp() {
        telegramClient = mockk()
        entityResolver = mockk()
        guardrailService = mockk(relaxed = true)
        auditService = mockk(relaxed = true)
        objectMapper = jacksonObjectMapper().findAndRegisterModules()
        exchange = mockk(relaxed = true)

        tool = DownloadMediaTool(
            telegramClient = telegramClient,
            entityResolver = entityResolver,
            guardrailService = guardrailService,
            auditService = auditService,
            objectMapper = objectMapper,
            meterRegistry = SimpleMeterRegistry(),
        )
    }

    @Test
    fun `definition returns correct tool name`() {
        assertEquals("download_media", tool.definition().name())
    }

    @Test
    fun `downloads media successfully`() {
        val downloadResult = DownloadResult(
            localPath = "/tmp/tdlib/photo_42_100.jpg",
            fileName = "photo_42_100.jpg",
            mimeType = "image/jpeg",
            fileSize = 204800,
        )
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { telegramClient.downloadMedia(42L, 100L) } returns downloadResult

        val result = tool.execute(exchange, mapOf("chat_id" to 42, "message_id" to 100))

        assertFalse(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("photo_42_100.jpg"))
        assertTrue(text.contains("204800"))
        verify { guardrailService.validateChatAccess(42L) }
    }

    @Test
    fun `returns error when chat_id is missing`() {
        val result = tool.execute(exchange, mapOf("message_id" to 100))

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("chat_id is required"))
    }

    @Test
    fun `returns error when message_id is missing`() {
        every { entityResolver.resolve(42 as Any) } returns 42L

        val result = tool.execute(exchange, mapOf("chat_id" to 42))

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("message_id is required"))
    }

    @Test
    fun `returns error when message_id is not a number`() {
        every { entityResolver.resolve(42 as Any) } returns 42L

        val result = tool.execute(exchange, mapOf("chat_id" to 42, "message_id" to "not-a-number"))

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("message_id must be a valid number"))
    }

    @Test
    fun `returns error when client throws`() {
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { telegramClient.downloadMedia(42L, 100L) } throws RuntimeException("No media in message")

        val result = tool.execute(exchange, mapOf("chat_id" to 42, "message_id" to 100))

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("No media in message"))
    }

    @Test
    fun `accepts string-encoded message_id`() {
        val downloadResult = DownloadResult(
            localPath = "/tmp/tdlib/video_42_200.mp4",
            fileName = "video_42_200.mp4",
            mimeType = "video/mp4",
            fileSize = 1048576,
        )
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { telegramClient.downloadMedia(42L, 200L) } returns downloadResult

        val result = tool.execute(exchange, mapOf("chat_id" to 42, "message_id" to "200"))

        assertFalse(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("video/mp4"))
    }

    @Test
    fun `resolves username chat_id`() {
        val downloadResult = DownloadResult(
            localPath = "/tmp/tdlib/doc.pdf",
            fileName = "doc.pdf",
            mimeType = "application/pdf",
            fileSize = 512000,
        )
        every { entityResolver.resolve("@somechat" as Any) } returns 99L
        every { telegramClient.downloadMedia(99L, 55L) } returns downloadResult

        val result = tool.execute(exchange, mapOf("chat_id" to "@somechat", "message_id" to 55))

        assertFalse(result.isError)
        verify { guardrailService.validateChatAccess(99L) }
    }
}

