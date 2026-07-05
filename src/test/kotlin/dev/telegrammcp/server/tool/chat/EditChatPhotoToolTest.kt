package dev.telegrammcp.server.tool.chat

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EditChatPhotoToolTest {

    private lateinit var telegramClient: TelegramClientService
    private lateinit var entityResolver: EntityResolverService
    private lateinit var guardrailService: GuardrailService
    private lateinit var operationGuardService: OperationGuardService
    private lateinit var fileSecurityService: FileSecurityService
    private lateinit var auditService: AuditService
    private lateinit var objectMapper: ObjectMapper
    private lateinit var tool: EditChatPhotoTool
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

        tool = EditChatPhotoTool(
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
        assertEquals("edit_chat_photo", tool.definition().name())
    }

    @Test
    fun `sets chat photo successfully`() {
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { fileSecurityService.validateForUpload("/tmp/photo.jpg") } returns Path.of("/tmp/photo.jpg")
        every { telegramClient.setChatPhoto(42L, any()) } returns true

        val result = tool.execute(
            exchange,
            mapOf("chat_id" to 42, "file_path" to "/tmp/photo.jpg"),
        )

        assertFalse(result.isError)
        verify { operationGuardService.checkPermission("edit_chat_photo", any()) }
        verify { fileSecurityService.validateForUpload("/tmp/photo.jpg") }
    }

    @Test
    fun `returns error when chat_id is missing`() {
        val result = tool.execute(exchange, mapOf("file_path" to "/tmp/photo.jpg"))
        assertTrue(result.isError)
    }

    @Test
    fun `returns error when file_path is missing`() {
        every { entityResolver.resolve(42 as Any) } returns 42L
        val result = tool.execute(exchange, mapOf("chat_id" to 42))
        assertTrue(result.isError)
    }
}
