package dev.telegrammcp.server.tool.user

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.service.AuditService
import dev.telegrammcp.server.service.FileSecurityService
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

class SetProfilePhotoToolTest {

    private lateinit var telegramClient: TelegramClientService
    private lateinit var operationGuardService: OperationGuardService
    private lateinit var fileSecurityService: FileSecurityService
    private lateinit var auditService: AuditService
    private lateinit var objectMapper: ObjectMapper
    private lateinit var tool: SetProfilePhotoTool
    private lateinit var exchange: McpSyncServerExchange

    @BeforeEach
    fun setUp() {
        telegramClient = mockk()
        operationGuardService = mockk(relaxed = true)
        fileSecurityService = mockk(relaxed = true)
        auditService = mockk(relaxed = true)
        objectMapper = jacksonObjectMapper().findAndRegisterModules()
        exchange = mockk(relaxed = true)

        tool = SetProfilePhotoTool(
            telegramClient = telegramClient,
            operationGuardService = operationGuardService,
            fileSecurityService = fileSecurityService,
            auditService = auditService,
            objectMapper = objectMapper,
            meterRegistry = SimpleMeterRegistry(),
        )
    }

    @Test
    fun `definition returns correct tool name`() {
        assertEquals("set_profile_photo", tool.definition().name())
    }

    @Test
    fun `sets profile photo successfully`() {
        every { fileSecurityService.validateForUpload("/tmp/photo.jpg") } returns Path.of("/tmp/photo.jpg")
        every { telegramClient.setProfilePhoto(any()) } returns true

        val result = tool.execute(exchange, mapOf("file_path" to "/tmp/photo.jpg"))

        assertFalse(result.isError)
        verify { operationGuardService.checkPermission("set_profile_photo", any()) }
        verify { fileSecurityService.validateForUpload("/tmp/photo.jpg") }
    }

    @Test
    fun `returns error when file_path is missing`() {
        val result = tool.execute(exchange, emptyMap())
        assertTrue(result.isError)
    }
}
