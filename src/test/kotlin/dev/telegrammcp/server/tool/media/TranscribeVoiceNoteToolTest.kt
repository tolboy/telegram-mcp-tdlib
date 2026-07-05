package dev.telegrammcp.server.tool.media

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.model.VoiceTranscription
import dev.telegrammcp.server.model.VoiceTranscriptionStatus
import dev.telegrammcp.server.service.AuditService
import dev.telegrammcp.server.service.EntityResolverService
import dev.telegrammcp.server.service.GuardrailService
import dev.telegrammcp.server.service.OperationGuardService
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

class TranscribeVoiceNoteToolTest {

    private lateinit var telegramClient: TelegramClientService
    private lateinit var entityResolver: EntityResolverService
    private lateinit var guardrailService: GuardrailService
    private lateinit var operationGuardService: OperationGuardService
    private lateinit var auditService: AuditService
    private lateinit var objectMapper: ObjectMapper
    private lateinit var tool: TranscribeVoiceNoteTool
    private lateinit var exchange: McpSyncServerExchange

    @BeforeEach
    fun setUp() {
        telegramClient = mockk()
        entityResolver = mockk()
        guardrailService = mockk(relaxed = true)
        operationGuardService = mockk(relaxed = true)
        auditService = mockk(relaxed = true)
        objectMapper = jacksonObjectMapper().findAndRegisterModules()
        exchange = mockk(relaxed = true)
        tool = TranscribeVoiceNoteTool(
            telegramClient = telegramClient,
            entityResolver = entityResolver,
            guardrailService = guardrailService,
            operationGuardService = operationGuardService,
            auditService = auditService,
            objectMapper = objectMapper,
            meterRegistry = SimpleMeterRegistry(),
        )
    }

    @Test
    fun `definition describes native transcription without a new message`() {
        val definition = tool.definition()

        assertEquals("transcribe_voice_note", definition.name())
        assertTrue(definition.description().orEmpty().contains("does not send a new message"))
    }

    @Test
    fun `returns completed Telegram transcription`() {
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { telegramClient.transcribeVoiceNote(42L, 100L) } returns VoiceTranscription(
            chatId = 42L,
            messageId = 100L,
            status = VoiceTranscriptionStatus.COMPLETED,
            text = "Hello from Telegram",
        )

        val result = tool.execute(exchange, mapOf("chat_id" to 42, "message_id" to "100"))

        assertFalse(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("COMPLETED"))
        assertTrue(text.contains("Hello from Telegram"))
        verify { operationGuardService.checkPermission("transcribe_voice_note", any()) }
        verify { guardrailService.validateChatAccess(42L) }
        verify { auditService.record("transcribe_voice_note", any(), any(), any(), any()) }
    }

    @Test
    fun `returns pending state when Telegram is still transcribing`() {
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { telegramClient.transcribeVoiceNote(42L, 100L) } returns VoiceTranscription(
            chatId = 42L,
            messageId = 100L,
            status = VoiceTranscriptionStatus.PENDING,
            partialText = "Hello",
        )

        val result = tool.execute(exchange, mapOf("chat_id" to 42, "message_id" to 100))

        assertFalse(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("PENDING"))
        assertTrue(text.contains("Hello"))
    }

    @Test
    fun `does not call Telegram when message id is invalid`() {
        every { entityResolver.resolve(42 as Any) } returns 42L

        val result = tool.execute(exchange, mapOf("chat_id" to 42, "message_id" to "not-a-number"))

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("message_id must be a valid number"))
        verify(exactly = 0) { telegramClient.transcribeVoiceNote(any(), any()) }
    }

    @Test
    fun `returns Telegram Premium or trial errors without hiding them`() {
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { telegramClient.transcribeVoiceNote(42L, 100L) } throws IllegalStateException("Premium subscription is required")

        val result = tool.execute(exchange, mapOf("chat_id" to 42, "message_id" to 100))

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("Premium subscription is required"))
    }
}
