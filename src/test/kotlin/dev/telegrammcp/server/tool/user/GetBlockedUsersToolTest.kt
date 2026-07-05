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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class GetBlockedUsersToolTest {

    private lateinit var telegramClient: TelegramClientService
    private lateinit var auditService: AuditService
    private lateinit var objectMapper: ObjectMapper
    private lateinit var tool: GetBlockedUsersTool
    private lateinit var exchange: McpSyncServerExchange

    @BeforeEach
    fun setUp() {
        telegramClient = mockk()
        auditService = mockk(relaxed = true)
        objectMapper = jacksonObjectMapper().findAndRegisterModules()
        exchange = mockk(relaxed = true)

        tool = GetBlockedUsersTool(
            telegramClient = telegramClient,
            auditService = auditService,
            objectMapper = objectMapper,
            meterRegistry = SimpleMeterRegistry(),
        )
    }

    @Test
    fun `definition returns correct tool name`() {
        assertEquals("get_blocked_users", tool.definition().name())
    }

    @Test
    fun `returns blocked users successfully`() {
        val users = listOf(
            UserInfo(1L, "Bob", isBot = false),
        )
        every { telegramClient.getBlockedUsers(100) } returns users

        val result = tool.execute(exchange, emptyMap())

        assertFalse(result.isError)
    }

    @Test
    fun `respects custom limit`() {
        every { telegramClient.getBlockedUsers(10) } returns emptyList()

        val result = tool.execute(exchange, mapOf("limit" to 10))

        assertFalse(result.isError)
    }
}
