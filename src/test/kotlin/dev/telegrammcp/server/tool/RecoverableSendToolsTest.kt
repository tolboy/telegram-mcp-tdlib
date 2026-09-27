package dev.telegrammcp.server.tool

import dev.telegrammcp.server.tool.executeChecked

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.model.TelegramMessage
import dev.telegrammcp.server.model.ScheduledMessage
import dev.telegrammcp.server.service.*
import dev.telegrammcp.server.tool.media.*
import dev.telegrammcp.server.tool.message.*
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.*
import io.modelcontextprotocol.server.McpSyncServerExchange
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.*

class RecoverableSendToolsTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `media forwarding and scheduling replay without another send or quota charge`() {
        val mapper = jacksonObjectMapper().findAndRegisterModules()
        val client = mockk<TelegramClientService>()
        val resolver = mockk<EntityResolverService> { every { resolve(any<Any>()) } answers { firstArg<Number>().toLong() } }
        val guardrails = mockk<GuardrailService>(relaxed = true)
        val guard = mockk<OperationGuardService>(relaxed = true)
        val audit = mockk<AuditService>(relaxed = true)
        val file = Files.writeString(directory.resolve("media.dat"), "content")
        val files = mockk<FileSecurityService> { every { validateForUpload(any()) } returns file }
        val operations = SendOperationService(
            mockk { every { currentAccount() } returns "work" },
            mockk { every { applicationDataDirectory } returns directory }, mapper,
        )
        val metrics = SimpleMeterRegistry()
        val message = TelegramMessage(100, 42, "chat", "sender", text = "text", date = Instant.now())
        every { client.sendFile(any(), any(), any()) } returns message
        every { client.sendVoice(any(), any(), any(), any()) } returns message
        every { client.sendSticker(any(), any(), any()) } returns message
        every { client.forwardMessages(any(), any(), any()) } returns listOf(message, message.copy(messageId = 101))
        every { client.scheduleMessage(any(), any(), any(), any(), any(), any()) } returns ScheduledMessage(message)
        val cases = listOf(
            SendFileTool(client, resolver, guardrails, files, guard, audit, mapper, metrics, operations) to
                mapOf("chat_id" to 42, "file_path" to file.toString()),
            SendVoiceTool(client, resolver, guardrails, files, guard, audit, mapper, metrics, operations) to
                mapOf("chat_id" to 42, "file_path" to file.toString()),
            SendStickerTool(client, resolver, guardrails, files, guard, audit, mapper, metrics, operations) to
                mapOf("chat_id" to 42, "file_path" to file.toString()),
            ForwardMessageTool(client, resolver, guardrails, guard, audit, mapper, metrics, operations) to
                mapOf("from_chat_id" to 40, "to_chat_id" to 42, "message_ids" to listOf(1, 2)),
            ScheduleMessageTool(client, resolver, guardrails, guard, audit, mapper, metrics, operations) to
                mapOf("chat_id" to 42, "text" to "later", "send_at" to Instant.now().plusSeconds(3600).epochSecond),
        )
        val exchange = mockk<McpSyncServerExchange>(relaxed = true)
        for ((tool, input) in cases) {
            val arguments = input + ("idempotency_key" to tool.definition().name())
            assertFalse(tool.executeChecked(exchange, arguments).isError, tool.definition().name())
            val result = tool.executeChecked(exchange, arguments)
            assertFalse(result.isError, tool.definition().name())
            val receipt = (result.structuredContent() as Map<*, *>)["data"] as Map<*, *>
            assertEquals(true, receipt["replayed"])
            assertEquals(if (tool is ScheduleMessageTool) "SCHEDULED" else "SENT", receipt["status"])
            verify(exactly = 1) { guard.checkPermission(tool.definition().name(), arguments) }
        }
        verify(exactly = 1) { client.sendFile(any(), any(), any()) }
        verify(exactly = 1) { client.sendVoice(any(), any(), any(), any()) }
        verify(exactly = 1) { client.sendSticker(any(), any(), any()) }
        verify(exactly = 1) { client.forwardMessages(any(), any(), any()) }
        verify(exactly = 1) { client.scheduleMessage(any(), any(), any(), any(), any(), any()) }

        val past = mapOf<String, Any>("chat_id" to 42, "text" to "later", "send_at" to 100, "idempotency_key" to "past")
        operations.execute(past, 42, listOf("schedule_message", 42L, "later", 100, 0, false, "PLAIN"), {}, scheduled = true) {
            ScheduledMessage(message)
        }
        assertFalse(cases.last().first.executeChecked(exchange, past).isError)
        verify(exactly = 1) { client.scheduleMessage(any(), any(), any(), any(), any(), any()) }

        Files.writeString(file, "changed content")
        val changed = cases.first().first.executeChecked(exchange, cases.first().second + ("idempotency_key" to "send_file"))
        assertTrue(changed.isError)
        verify(exactly = 1) { client.sendFile(any(), any(), any()) }
    }
}
