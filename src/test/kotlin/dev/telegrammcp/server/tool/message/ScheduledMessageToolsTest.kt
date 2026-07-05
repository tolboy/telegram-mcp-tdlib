package dev.telegrammcp.server.tool.message

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.model.ParseMode
import dev.telegrammcp.server.model.ScheduledMessage
import dev.telegrammcp.server.model.TelegramMessage
import dev.telegrammcp.server.service.AuditService
import dev.telegrammcp.server.service.EntityResolverService
import dev.telegrammcp.server.service.GuardrailService
import dev.telegrammcp.server.service.OperationGuardService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.modelcontextprotocol.spec.McpSchema
import io.modelcontextprotocol.server.McpSyncServerExchange
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScheduledMessageToolsTest {

    private val telegramClient = mockk<TelegramClientService>()
    private val entityResolver = mockk<EntityResolverService>()
    private val guardrails = mockk<GuardrailService>(relaxed = true)
    private val operationGuard = mockk<OperationGuardService>(relaxed = true)
    private val audit = mockk<AuditService>(relaxed = true)
    private val exchange = mockk<McpSyncServerExchange>(relaxed = true)
    private val mapper = jacksonObjectMapper().findAndRegisterModules()

    @Test
    fun `schedules a future ISO instant with normal write safeguards`() {
        val tool = ScheduleMessageTool(telegramClient, entityResolver, guardrails, operationGuard, audit, mapper, SimpleMeterRegistry())
        val future = Instant.now().plusSeconds(600)
        every { entityResolver.resolve(42 as Any) } returns 42L
        every {
            telegramClient.scheduleMessage(42L, "planned", future.epochSecond.toInt(), 0, false, ParseMode.PLAIN)
        } returns scheduled(42L)

        val result = tool.execute(exchange, mapOf("chat_id" to 42, "text" to "planned", "send_at" to future.toString()))

        assertFalse(result.isError)
        verify { operationGuard.checkPermission("schedule_message", any()) }
        verify { guardrails.validateChatAccess(42L) }
        verify { guardrails.validateInput("planned") }
    }

    @Test
    fun `rejects a past scheduled time before Telegram is called`() {
        val tool = ScheduleMessageTool(telegramClient, entityResolver, guardrails, operationGuard, audit, mapper, SimpleMeterRegistry())
        every { entityResolver.resolve(42 as Any) } returns 42L

        val result = tool.execute(exchange, mapOf("chat_id" to 42, "text" to "planned", "send_at" to 1))

        assertTrue(result.isError)
        verify(exactly = 0) { telegramClient.scheduleMessage(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `cancelling a scheduled message requires the operation guard`() {
        val tool = CancelScheduledMessageTool(telegramClient, entityResolver, guardrails, operationGuard, audit, mapper, SimpleMeterRegistry())
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { telegramClient.cancelScheduledMessage(42L, 7L) } returns true

        val result = tool.execute(exchange, mapOf("chat_id" to 42, "message_id" to 7))

        assertFalse(result.isError)
        verify { operationGuard.checkPermission("cancel_scheduled_message", any()) }
        verify { telegramClient.cancelScheduledMessage(42L, 7L) }
    }

    @Test
    fun `rescheduling returns the replacement message id`() {
        val tool = RescheduleMessageTool(telegramClient, entityResolver, guardrails, operationGuard, audit, mapper, SimpleMeterRegistry())
        val future = Instant.now().plusSeconds(600)
        every { entityResolver.resolve(42 as Any) } returns 42L
        every {
            telegramClient.rescheduleMessage(42L, 7L, future.epochSecond.toInt(), 0)
        } returns scheduled(42L, 9L)

        val result = tool.execute(exchange, mapOf("chat_id" to 42, "message_id" to 7, "send_at" to future.toString()))
        val payload = mapper.readTree((result.content.first() as McpSchema.TextContent).text())

        assertFalse(result.isError)
        assertEquals(9L, payload["message_id"].asLong())
        assertEquals(7L, payload["previous_message_id"].asLong())
        verify { operationGuard.checkPermission("reschedule_message", any()) }
    }

    private fun scheduled(chatId: Long, messageId: Long = 7L) = ScheduledMessage(
        message = TelegramMessage(
            messageId = messageId,
            chatId = chatId,
            chatTitle = null,
            senderName = "Me",
            text = "planned",
            date = Instant.now(),
        ),
        sendAt = Instant.now().plusSeconds(600),
    )
}
