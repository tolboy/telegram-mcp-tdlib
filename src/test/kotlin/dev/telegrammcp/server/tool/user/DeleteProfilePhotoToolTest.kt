package dev.telegrammcp.server.tool.user

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.service.AuditService
import dev.telegrammcp.server.service.OperationGuardService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.modelcontextprotocol.server.McpSyncServerExchange
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DeleteProfilePhotoToolTest {

    private lateinit var telegramClient: TelegramClientService
    private lateinit var operationGuardService: OperationGuardService
    private lateinit var auditService: AuditService
    private lateinit var objectMapper: ObjectMapper
    private lateinit var tool: DeleteProfilePhotoTool
    private lateinit var exchange: McpSyncServerExchange

    @BeforeEach
    fun setUp() {
        telegramClient = mockk()
        operationGuardService = mockk(relaxed = true)
        auditService = mockk(relaxed = true)
        objectMapper = jacksonObjectMapper().findAndRegisterModules()
        exchange = mockk(relaxed = true)

        tool = DeleteProfilePhotoTool(
            telegramClient = telegramClient,
            operationGuardService = operationGuardService,
            auditService = auditService,
            objectMapper = objectMapper,
            meterRegistry = SimpleMeterRegistry(),
        )
    }

    @Test
    fun `definition returns correct tool name`() {
        assertEquals("delete_profile_photo", tool.definition().name())
    }

    @Test
    fun `deletes profile photo successfully`() {
        every { telegramClient.deleteProfilePhoto(null) } returns true

        val result = tool.execute(exchange, mapOf("confirmed" to true))

        assertFalse(result.isError)
        verify { operationGuardService.checkPermission("delete_profile_photo", any()) }
    }

    @Test
    fun `deletes specific photo by id`() {
        every { telegramClient.deleteProfilePhoto(123L) } returns true

        val result = tool.execute(exchange, mapOf("photo_id" to 123, "confirmed" to true))

        assertFalse(result.isError)
        verify { telegramClient.deleteProfilePhoto(123L) }
    }
}
