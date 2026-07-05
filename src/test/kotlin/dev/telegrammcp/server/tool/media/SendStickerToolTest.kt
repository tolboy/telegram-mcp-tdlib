package dev.telegrammcp.server.tool.media

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.model.TelegramMessage
import dev.telegrammcp.server.service.AuditService
import dev.telegrammcp.server.service.EntityResolverService
import dev.telegrammcp.server.service.FileSecurityService
import dev.telegrammcp.server.service.GuardrailService
import dev.telegrammcp.server.service.OperationGuardService
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
import kotlin.test.assertTrue

class SendStickerToolTest {

    private lateinit var telegramClient: TelegramClientService
    private lateinit var entityResolver: EntityResolverService
    private lateinit var guardrailService: GuardrailService
    private lateinit var operationGuardService: OperationGuardService
    private lateinit var fileSecurityService: FileSecurityService
    private lateinit var auditService: AuditService
    private lateinit var objectMapper: ObjectMapper
    private lateinit var tool: SendStickerTool
    private lateinit var exchange: McpSyncServerExchange

    @BeforeEach
    fun setUp() {
        telegramClient = mockk()
        entityResolver = mockk()
        guardrailService = mockk(relaxed = true)
        operationGuardService = mockk(relaxed = true)
        fileSecurityService = mockk(relaxed = true)
        auditService = mockk(relaxed = true)
        objectMapper = jacksonObjectMapper().findAndRegisterModules()
        exchange = mockk(relaxed = true)

        tool = SendStickerTool(
            telegramClient = telegramClient,
            entityResolver = entityResolver,
            guardrailService = guardrailService,
            operationGuardService = operationGuardService,
            fileSecurityService = fileSecurityService,
            auditService = auditService,
            objectMapper = objectMapper,
            meterRegistry = SimpleMeterRegistry(),
        )
    }

    @Test
    fun `definition returns correct tool name`() {
        assertEquals("send_sticker", tool.definition().name())
    }

    @Test
    fun `sends sticker successfully`() {
        val msg = TelegramMessage(1, 42, "Test", "Alice", text = null, date = Instant.now(), mediaType = "sticker")
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { fileSecurityService.validateForUpload("/tmp/sticker.webp") } returns Path.of("/tmp/sticker.webp")
        every { telegramClient.sendSticker(42L, any(), null) } returns msg

        val result = tool.execute(
            exchange,
            mapOf("chat_id" to 42, "file_path" to "/tmp/sticker.webp"),
        )

        assertFalse(result.isError)
        verify { operationGuardService.checkPermission("send_sticker", any()) }
        verify { fileSecurityService.validateForUpload("/tmp/sticker.webp") }
    }

    @Test
    fun `returns error when file_path is missing`() {
        every { entityResolver.resolve(42 as Any) } returns 42L
        val result = tool.execute(exchange, mapOf("chat_id" to 42))
        assertTrue(result.isError)
    }
}
