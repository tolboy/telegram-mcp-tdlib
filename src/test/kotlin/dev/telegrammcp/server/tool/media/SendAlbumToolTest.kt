package dev.telegrammcp.server.tool.media

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.model.AlbumItem
import dev.telegrammcp.server.model.AlbumItemType
import dev.telegrammcp.server.model.TelegramMessage
import dev.telegrammcp.server.service.AuditService
import dev.telegrammcp.server.service.EntityResolverService
import dev.telegrammcp.server.service.FileSecurityService
import dev.telegrammcp.server.service.GuardrailService
import dev.telegrammcp.server.service.OperationGuardService
import dev.telegrammcp.server.tool.executeChecked
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.modelcontextprotocol.server.McpSyncServerExchange
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SendAlbumToolTest {

    private lateinit var telegramClient: TelegramClientService
    private lateinit var entityResolver: EntityResolverService
    private lateinit var guardrailService: GuardrailService
    private lateinit var operationGuardService: OperationGuardService
    private lateinit var fileSecurityService: FileSecurityService
    private lateinit var tool: SendAlbumTool
    private val exchange = mockk<McpSyncServerExchange>(relaxed = true)

    @BeforeEach
    fun setUp() {
        telegramClient = mockk()
        entityResolver = mockk()
        guardrailService = mockk(relaxed = true)
        operationGuardService = mockk(relaxed = true)
        fileSecurityService = mockk(relaxed = true)
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { fileSecurityService.validateForUpload(any<String>()) } answers { Path.of("/safe" + firstArg<String>()) }
        tool = SendAlbumTool(
            telegramClient = telegramClient,
            entityResolver = entityResolver,
            guardrailService = guardrailService,
            fileSecurityService = fileSecurityService,
            operationGuardService = operationGuardService,
            auditService = mockk<AuditService>(relaxed = true),
            objectMapper = jacksonObjectMapper().findAndRegisterModules(),
            meterRegistry = SimpleMeterRegistry(),
        )
    }

    private fun item(path: String, type: String, caption: String? = null) =
        buildMap<String, Any> { put("file_path", path); put("type", type); caption?.let { put("caption", it) } }

    private fun message(id: Long) = TelegramMessage(id, 42, "Test", "Me", text = null, date = Instant.EPOCH, mediaType = "photo")

    @Test
    fun `sends validated photos and videos as one album`() {
        every { telegramClient.sendAlbum(42L, any()) } returns listOf(message(1), message(2))

        val result = tool.executeChecked(exchange, mapOf("chat_id" to 42,
            "items" to listOf(item("/tmp/a.jpg", "photo", "hi"), item("/tmp/b.mp4", "VIDEO"))))

        assertFalse(result.isError)
        val data = (result.structuredContent() as Map<*, *>)["data"] as List<*>
        assertEquals(2, data.size)
        verify { operationGuardService.checkPermission("send_album", any()) }
        verify {
            telegramClient.sendAlbum(42L, listOf(
                AlbumItem("/safe/tmp/a.jpg", AlbumItemType.PHOTO, "hi"),
                AlbumItem("/safe/tmp/b.mp4", AlbumItemType.VIDEO, null),
            ))
        }
        verify { fileSecurityService.validateForUpload("/tmp/a.jpg") }
        verify { fileSecurityService.validateForUpload("/tmp/b.mp4") }
    }

    @Test
    fun `rejects too few and too many items`() {
        val one = tool.executeChecked(exchange, mapOf("chat_id" to 42, "items" to listOf(item("/tmp/a.jpg", "photo"))))
        val eleven = tool.executeChecked(exchange,
            mapOf("chat_id" to 42, "items" to (1..11).map { item("/tmp/$it.jpg", "photo") }))

        assertTrue(one.isError)
        assertTrue(eleven.isError)
        verify(exactly = 0) { telegramClient.sendAlbum(any(), any()) }
    }

    @Test
    fun `rejects documents mixed with photos before any upload check`() {
        val result = tool.executeChecked(exchange, mapOf("chat_id" to 42,
            "items" to listOf(item("/tmp/a.jpg", "photo"), item("/tmp/b.pdf", "document"))))

        assertTrue(result.isError)
        verify(exactly = 0) { fileSecurityService.validateForUpload(any<String>()) }
        verify(exactly = 0) { telegramClient.sendAlbum(any(), any()) }
    }

    @Test
    fun `rejects unknown item types and missing paths`() {
        val badType = tool.executeChecked(exchange, mapOf("chat_id" to 42,
            "items" to listOf(item("/tmp/a.jpg", "photo"), item("/tmp/b.gif", "animation"))))
        val noPath = tool.executeChecked(exchange, mapOf("chat_id" to 42,
            "items" to listOf(item("/tmp/a.jpg", "photo"), mapOf("type" to "photo"))))

        assertTrue(badType.isError)
        assertTrue(noPath.isError)
    }

    @Test
    fun `grouping rule follows Telegram`() {
        assertNull(SendAlbumTool.groupingError(listOf(AlbumItemType.PHOTO, AlbumItemType.VIDEO)))
        assertNull(SendAlbumTool.groupingError(listOf(AlbumItemType.DOCUMENT, AlbumItemType.DOCUMENT)))
        assertNull(SendAlbumTool.groupingError(listOf(AlbumItemType.AUDIO, AlbumItemType.AUDIO)))
        assertTrue(SendAlbumTool.groupingError(listOf(AlbumItemType.AUDIO, AlbumItemType.DOCUMENT)) != null)
        assertTrue(SendAlbumTool.groupingError(listOf(AlbumItemType.VIDEO, AlbumItemType.AUDIO)) != null)
    }

    @Test
    fun `advertises an idempotency key`() {
        assertTrue("idempotency_key" in tool.definition().inputSchema()["properties"] as Map<*, *>)
    }
}
