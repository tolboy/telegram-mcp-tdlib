package dev.telegrammcp.server.tool.user

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.model.UserInfo
import dev.telegrammcp.server.service.AuditService
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

class GetMeToolTest {

    private lateinit var telegramClient: TelegramClientService
    private lateinit var auditService: AuditService
    private lateinit var objectMapper: ObjectMapper
    private lateinit var tool: GetMeTool
    private lateinit var exchange: McpSyncServerExchange

    @BeforeEach
    fun setUp() {
        telegramClient = mockk()
        auditService = mockk(relaxed = true)
        objectMapper = jacksonObjectMapper().findAndRegisterModules()
        exchange = mockk(relaxed = true)

        tool = GetMeTool(
            telegramClient = telegramClient,
            auditService = auditService,
            objectMapper = objectMapper,
            meterRegistry = SimpleMeterRegistry(),
        )
    }

    @Test
    fun `definition returns correct tool name`() {
        assertEquals("get_me", tool.definition().name())
    }

    @Test
    fun `returns current user profile`() {
        val userInfo = UserInfo(
            userId = 123456, firstName = "John", lastName = "Doe",
            username = "johndoe", isBot = false, isPremium = true,
        )
        every { telegramClient.getMe() } returns userInfo

        val result = tool.execute(exchange, emptyMap())

        assertFalse(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("johndoe"))
        assertTrue(text.contains("John"))
    }

    @Test
    fun `returns error when client throws`() {
        every { telegramClient.getMe() } throws RuntimeException("Not authenticated")

        val result = tool.execute(exchange, emptyMap())

        assertTrue(result.isError)
        val text = (result.content.first() as McpSchema.TextContent).text()
        assertTrue(text.contains("Not authenticated"))
    }
}
