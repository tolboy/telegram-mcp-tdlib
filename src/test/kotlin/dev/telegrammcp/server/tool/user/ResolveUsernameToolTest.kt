package dev.telegrammcp.server.tool.user

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.model.ChatInfo
import dev.telegrammcp.server.model.ChatType
import dev.telegrammcp.server.model.UserInfo
import dev.telegrammcp.server.service.AuditService
import dev.telegrammcp.server.service.EntityResolverService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResolveUsernameToolTest {

    private lateinit var telegramClient: TelegramClientService
    private lateinit var entityResolver: EntityResolverService
    private lateinit var auditService: AuditService
    private lateinit var objectMapper: ObjectMapper
    private lateinit var tool: ResolveUsernameTool
    private lateinit var exchange: McpSyncServerExchange

    @BeforeEach
    fun setUp() {
        telegramClient = mockk()
        entityResolver = mockk()
        auditService = mockk(relaxed = true)
        objectMapper = jacksonObjectMapper().findAndRegisterModules()
        exchange = mockk(relaxed = true)

        tool = ResolveUsernameTool(
            telegramClient = telegramClient,
            entityResolver = entityResolver,
            auditService = auditService,
            objectMapper = objectMapper,
            meterRegistry = SimpleMeterRegistry(),
        )
    }

    @Test
    fun `definition returns correct tool name`() {
        assertEquals("resolve_username", tool.definition().name())
    }

    @Test
    fun `resolves username to user info`() {
        every { entityResolver.resolve("@testuser" as Any) } returns 42L
        every { telegramClient.getUser(42L) } returns UserInfo(
            userId = 42, firstName = "Test", username = "testuser",
        )

        val result = tool.execute(exchange, mapOf("identifier" to "@testuser"))

        assertFalse(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("testuser"))
        assertTrue(text.contains("42"))
    }

    @Test
    fun `resolves negative ID to chat info`() {
        every { entityResolver.resolve(-100123 as Any) } returns -100123L
        every { telegramClient.getChat(-100123L) } returns ChatInfo(
            chatId = -100123, title = "My Channel", type = ChatType.CHANNEL,
        )

        val result = tool.execute(exchange, mapOf("identifier" to -100123))

        assertFalse(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("My Channel"))
    }

    @Test
    fun `returns error when identifier is missing`() {
        val result = tool.execute(exchange, emptyMap())

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("identifier is required"))
    }

    @Test
    fun `returns error when resolution fails`() {
        every { entityResolver.resolve("@unknown" as Any) } throws
            dev.telegrammcp.server.exception.EntityNotFoundException("@unknown")

        val result = tool.execute(exchange, mapOf("identifier" to "@unknown"))

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("not found"))
    }
}
