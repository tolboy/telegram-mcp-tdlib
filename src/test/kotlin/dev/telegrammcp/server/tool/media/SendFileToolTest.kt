package dev.telegrammcp.server.tool.media

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.exception.FileSecurityException
import dev.telegrammcp.server.exception.ReadOnlyModeException
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
import io.modelcontextprotocol.spec.McpSchema
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SendFileToolTest {

    private lateinit var telegramClient: TelegramClientService
    private lateinit var entityResolver: EntityResolverService
    private lateinit var guardrailService: GuardrailService
    private lateinit var fileSecurityService: FileSecurityService
    private lateinit var operationGuardService: OperationGuardService
    private lateinit var auditService: AuditService
    private lateinit var objectMapper: ObjectMapper
    private lateinit var tool: SendFileTool
    private lateinit var exchange: McpSyncServerExchange

    @BeforeEach
    fun setUp() {
        telegramClient = mockk()
        entityResolver = mockk()
        guardrailService = mockk(relaxed = true)
        fileSecurityService = mockk()
        operationGuardService = mockk(relaxed = true)
        auditService = mockk(relaxed = true)
        objectMapper = jacksonObjectMapper().findAndRegisterModules()
        exchange = mockk(relaxed = true)

        tool = SendFileTool(
            telegramClient = telegramClient,
            entityResolver = entityResolver,
            guardrailService = guardrailService,
            fileSecurityService = fileSecurityService,
            operationGuardService = operationGuardService,
            auditService = auditService,
            objectMapper = objectMapper,
            meterRegistry = SimpleMeterRegistry(),
        )
    }

    @Test
    fun `sends file successfully`() {
        val sentMsg = TelegramMessage(
            messageId = 100, chatId = 42, chatTitle = "Test",
            senderName = "Bot", text = "[Document: test.pdf]", date = Instant.now(),
            mediaType = "document",
        )
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { fileSecurityService.validateForUpload("/data/test.pdf") } returns Path.of("/data/test.pdf")
        every { telegramClient.sendFile(42L, any(), null) } returns sentMsg

        val result = tool.execute(
            exchange,
            mapOf("chat_id" to 42, "file_path" to "/data/test.pdf"),
        )

        assertFalse(result.isError)
        verify { operationGuardService.checkPermission("send_file", any()) }
        verify { guardrailService.validateChatAccess(42L) }
    }

    @Test
    fun `blocked by read-only mode`() {
        every { operationGuardService.checkPermission("send_file", any()) } throws
            ReadOnlyModeException("send_file")

        val result = tool.execute(
            exchange,
            mapOf("chat_id" to 42, "file_path" to "/data/test.pdf"),
        )

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("read-only"))
    }

    @Test
    fun `blocked by file security violation`() {
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { fileSecurityService.validateForUpload("/etc/passwd") } throws
            FileSecurityException("Path is outside allowed file system roots")

        val result = tool.execute(
            exchange,
            mapOf("chat_id" to 42, "file_path" to "/etc/passwd"),
        )

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("security violation"))
    }

    @Test
    fun `returns error when file_path is missing`() {
        every { entityResolver.resolve(42 as Any) } returns 42L

        val result = tool.execute(exchange, mapOf("chat_id" to 42))

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("file_path is required"))
    }

    @Test
    fun `sends file with caption`() {
        val sentMsg = TelegramMessage(
            messageId = 100, chatId = 42, chatTitle = "Test",
            senderName = "Bot", text = "See this file", date = Instant.now(),
            mediaType = "document",
        )
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { fileSecurityService.validateForUpload("/data/doc.pdf") } returns Path.of("/data/doc.pdf")
        every { telegramClient.sendFile(42L, any(), "See this file") } returns sentMsg

        val result = tool.execute(
            exchange,
            mapOf("chat_id" to 42, "file_path" to "/data/doc.pdf", "caption" to "See this file"),
        )

        assertFalse(result.isError)
        verify { guardrailService.validateInput("See this file") }
    }
}
