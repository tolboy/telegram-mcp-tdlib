package dev.telegrammcp.server.tool.message

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.model.TelegramMessage
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
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CreatePollToolTest {

    private lateinit var telegramClient: TelegramClientService
    private lateinit var entityResolver: EntityResolverService
    private lateinit var guardrailService: GuardrailService
    private lateinit var operationGuardService: OperationGuardService
    private lateinit var auditService: AuditService
    private lateinit var objectMapper: ObjectMapper
    private lateinit var tool: CreatePollTool
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

        tool = CreatePollTool(
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
    fun `definition returns correct tool name`() {
        assertEquals("create_poll", tool.definition().name())
    }

    @Test
    fun `creates poll with defaults`() {
        val pollMsg = TelegramMessage(
            messageId = 200, chatId = 42, chatTitle = "Chat",
            senderName = "Me", text = null, date = Instant.now(),
        )
        every { entityResolver.resolve(42 as Any) } returns 42L
        every {
            telegramClient.sendPoll(42L, "Best language?", listOf("Kotlin", "Java"), true, false)
        } returns pollMsg

        val result = tool.execute(
            exchange,
            mapOf(
                "chat_id" to 42,
                "question" to "Best language?",
                "options" to listOf("Kotlin", "Java"),
            ),
        )

        assertFalse(result.isError)
        verify { operationGuardService.checkPermission("create_poll", any()) }
        verify { guardrailService.validateChatAccess(42L) }
    }

    @Test
    fun `creates non-anonymous poll with multiple answers`() {
        val pollMsg = TelegramMessage(
            messageId = 201, chatId = 42, chatTitle = "Chat",
            senderName = "Me", text = null, date = Instant.now(),
        )
        every { entityResolver.resolve(42 as Any) } returns 42L
        every {
            telegramClient.sendPoll(42L, "Pick all", listOf("A", "B", "C"), false, true)
        } returns pollMsg

        val result = tool.execute(
            exchange,
            mapOf(
                "chat_id" to 42,
                "question" to "Pick all",
                "options" to listOf("A", "B", "C"),
                "is_anonymous" to false,
                "allow_multiple_answers" to true,
            ),
        )

        assertFalse(result.isError)
    }

    @Test
    fun `returns error when question is missing`() {
        every { entityResolver.resolve(42 as Any) } returns 42L

        val result = tool.execute(
            exchange,
            mapOf("chat_id" to 42, "options" to listOf("A", "B")),
        )

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("question is required"))
    }

    @Test
    fun `returns error when options has fewer than 2 entries`() {
        every { entityResolver.resolve(42 as Any) } returns 42L

        val result = tool.execute(
            exchange,
            mapOf("chat_id" to 42, "question" to "Q?", "options" to listOf("OnlyOne")),
        )

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("at least 2"))
    }

    @Test
    fun `returns error when options exceeds 10 entries`() {
        every { entityResolver.resolve(42 as Any) } returns 42L

        val result = tool.execute(
            exchange,
            mapOf(
                "chat_id" to 42,
                "question" to "Q?",
                "options" to (1..11).map { "Option $it" },
            ),
        )

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("at most 10"))
    }
}
