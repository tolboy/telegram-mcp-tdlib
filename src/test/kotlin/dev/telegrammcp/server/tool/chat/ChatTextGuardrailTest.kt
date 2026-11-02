package dev.telegrammcp.server.tool.chat

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.config.McpSecurityProperties
import dev.telegrammcp.server.config.TelegramProperties
import dev.telegrammcp.server.service.AuditService
import dev.telegrammcp.server.service.EntityResolverService
import dev.telegrammcp.server.service.GuardrailService
import dev.telegrammcp.server.service.OperationGuardService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.mockk
import io.mockk.verify
import io.modelcontextprotocol.server.McpSyncServerExchange
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * Text that these tools publish (a group title, bot command descriptions) goes
 * through the configured input filter like every other outgoing text field.
 */
class ChatTextGuardrailTest {
    private val client = mockk<TelegramClientService>()
    private val resolver = mockk<EntityResolverService>()
    private val guard = GuardrailService(
        McpSecurityProperties(guardrails = McpSecurityProperties.GuardrailProps(blockedPatterns = listOf("SECRET_TOKEN"))),
        TelegramProperties(),
    )
    private val ops = mockk<OperationGuardService>(relaxed = true)
    private val audit = mockk<AuditService>(relaxed = true)
    private val mapper = jacksonObjectMapper().findAndRegisterModules()
    private val exchange = mockk<McpSyncServerExchange>(relaxed = true)

    @Test
    fun `create_group rejects a blocked title`() {
        val tool = CreateGroupTool(client, guard, ops, audit, mapper, SimpleMeterRegistry())
        val result = tool.execute(exchange, mapOf("title" to "team SECRET_TOKEN", "user_ids" to listOf(5)))
        assertTrue(result.isError)
        verify(exactly = 0) { client.createBasicGroup(any(), any()) }
    }

    @Test
    fun `set_bot_commands rejects a blocked command description`() {
        val tool = SetBotCommandsTool(client, resolver, guard, ops, audit, mapper, SimpleMeterRegistry())
        val result = tool.execute(exchange, mapOf(
            "scope" to "default",
            "commands" to listOf(mapOf("command" to "status", "description" to "SECRET_TOKEN")),
        ))
        assertTrue(result.isError)
        verify(exactly = 0) { client.setBotCommands(any(), any(), any()) }
    }
}
