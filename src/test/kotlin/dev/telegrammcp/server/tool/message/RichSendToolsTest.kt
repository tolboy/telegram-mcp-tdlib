package dev.telegrammcp.server.tool.message

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.model.ChatActionType
import dev.telegrammcp.server.model.ContactSpec
import dev.telegrammcp.server.model.TelegramMessage
import dev.telegrammcp.server.model.VenueSpec
import dev.telegrammcp.server.service.AuditService
import dev.telegrammcp.server.service.EntityResolverService
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
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RichSendToolsTest {

    private lateinit var telegramClient: TelegramClientService
    private lateinit var entityResolver: EntityResolverService
    private lateinit var guardrailService: GuardrailService
    private lateinit var operationGuardService: OperationGuardService
    private lateinit var auditService: AuditService
    private val objectMapper = jacksonObjectMapper().findAndRegisterModules()
    private val exchange = mockk<McpSyncServerExchange>(relaxed = true)
    private val message = TelegramMessage(5, 42, "Test", "Me", text = null, date = Instant.EPOCH, mediaType = "location")

    @BeforeEach
    fun setUp() {
        telegramClient = mockk()
        entityResolver = mockk()
        guardrailService = mockk(relaxed = true)
        operationGuardService = mockk(relaxed = true)
        auditService = mockk(relaxed = true)
        every { entityResolver.resolve(42 as Any) } returns 42L
    }

    private fun location() = SendLocationTool(telegramClient, entityResolver, guardrailService, operationGuardService,
        auditService, objectMapper, SimpleMeterRegistry())
    private fun venue() = SendVenueTool(telegramClient, entityResolver, guardrailService, operationGuardService,
        auditService, objectMapper, SimpleMeterRegistry())
    private fun contact() = SendContactTool(telegramClient, entityResolver, guardrailService, operationGuardService,
        auditService, objectMapper, SimpleMeterRegistry())
    private fun action() = SendChatActionTool(telegramClient, entityResolver, guardrailService, operationGuardService,
        auditService, objectMapper, SimpleMeterRegistry())

    private fun data(result: io.modelcontextprotocol.spec.McpSchema.CallToolResult) =
        (result.structuredContent() as Map<*, *>)["data"] as Map<*, *>

    @Test
    fun `send tools advertise an idempotency key`() {
        listOf(location(), venue(), contact()).forEach { tool ->
            val properties = tool.definition().inputSchema()["properties"] as Map<*, *>
            assertTrue("idempotency_key" in properties, tool.definition().name())
        }
        val actionProperties = action().definition().inputSchema()["properties"] as Map<*, *>
        assertFalse("idempotency_key" in actionProperties)
    }

    @Test
    fun `send_location passes coordinates and checks the guard`() {
        every { telegramClient.sendLocation(42L, 55.5, 37.5, 10.0) } returns message

        val result = location().executeChecked(exchange,
            mapOf("chat_id" to 42, "latitude" to 55.5, "longitude" to 37.5, "horizontal_accuracy" to 10))

        assertFalse(result.isError)
        assertEquals(5, (data(result)["messageId"] as Number).toInt())
        verify { operationGuardService.checkPermission("send_location", any()) }
        verify { guardrailService.validateChatAccess(42L) }
    }

    @Test
    fun `send_location rejects out-of-range coordinates before sending`() {
        val result = location().executeChecked(exchange, mapOf("chat_id" to 42, "latitude" to 91, "longitude" to 0))

        assertTrue(result.isError)
        verify(exactly = 0) { telegramClient.sendLocation(any(), any(), any(), any()) }
    }

    @Test
    fun `send_venue requires title and address`() {
        val missing = venue().executeChecked(exchange,
            mapOf("chat_id" to 42, "latitude" to 1, "longitude" to 2, "title" to "Cafe"))
        assertTrue(missing.isError)

        every { telegramClient.sendVenue(42L, any()) } returns message
        val result = venue().executeChecked(exchange, mapOf("chat_id" to 42, "latitude" to 1, "longitude" to 2,
            "title" to " Cafe ", "address" to "Main St", "provider" to "foursquare"))

        assertFalse(result.isError)
        verify { telegramClient.sendVenue(42L, VenueSpec(1.0, 2.0, "Cafe", "Main St", "foursquare", "", "")) }
    }

    @Test
    fun `send_contact builds the contact card`() {
        every { telegramClient.sendContact(42L, any()) } returns message

        val result = contact().executeChecked(exchange,
            mapOf("chat_id" to 42, "phone_number" to "+100", "first_name" to "Ann", "user_id" to 7))

        assertFalse(result.isError)
        verify { telegramClient.sendContact(42L, ContactSpec("+100", "Ann", "", "", 7L)) }
        verify { operationGuardService.checkPermission("send_contact", any()) }
    }

    @Test
    fun `send_contact rejects a missing first name`() {
        val result = contact().executeChecked(exchange, mapOf("chat_id" to 42, "phone_number" to "+100"))
        assertTrue(result.isError)
    }

    @Test
    fun `send_chat_action defaults to typing`() {
        every { telegramClient.sendChatAction(42L, ChatActionType.TYPING) } returns true

        val result = action().executeChecked(exchange, mapOf("chat_id" to 42))

        assertFalse(result.isError)
        assertEquals("typing", data(result)["action"])
        assertEquals(true, data(result)["sent"])
        verify { operationGuardService.checkPermission("send_chat_action", any()) }
    }

    @Test
    fun `send_chat_action maps a named action and rejects unknown ones`() {
        every { telegramClient.sendChatAction(42L, ChatActionType.UPLOADING_PHOTO) } returns true
        assertFalse(action().executeChecked(exchange, mapOf("chat_id" to 42, "action" to "UPLOADING_PHOTO")).isError)

        val bad = action().executeChecked(exchange, mapOf("chat_id" to 42, "action" to "dancing"))
        assertTrue(bad.isError)
    }
}
