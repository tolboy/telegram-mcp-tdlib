package dev.telegrammcp.server.tool.message

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.exception.ConfirmationRequiredException
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
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UnpinAllMessagesToolTest {

    private val telegramClient = mockk<TelegramClientService>()
    private val entityResolver = mockk<EntityResolverService>().also { every { it.resolve(42 as Any) } returns 42L }
    private val guardrailService = mockk<GuardrailService>(relaxed = true)
    private val operationGuardService = mockk<OperationGuardService>(relaxed = true)
    private val tool = UnpinAllMessagesTool(telegramClient, entityResolver, guardrailService, operationGuardService,
        mockk<AuditService>(relaxed = true), jacksonObjectMapper().findAndRegisterModules(), SimpleMeterRegistry())
    private val exchange = mockk<McpSyncServerExchange>(relaxed = true)

    @Test
    fun `tool is destructive and advertises confirmation`() {
        assertEquals("unpin_all_messages", tool.definition().name())
        assertTrue(OperationGuardService.annotationsFor("unpin_all_messages").destructiveHint())
        assertTrue("confirmed" in tool.definition().inputSchema()["properties"] as Map<*, *>)
    }

    @Test
    fun `unpins all messages after the guard`() {
        every { telegramClient.unpinAllMessages(42L) } returns true

        val result = tool.executeChecked(exchange, mapOf("chat_id" to 42, "confirmed" to true))

        assertFalse(result.isError)
        val data = (result.structuredContent() as Map<*, *>)["data"] as Map<*, *>
        assertEquals(true, data["unpinned_all"])
        verify { operationGuardService.checkPermission("unpin_all_messages", any()) }
    }

    @Test
    fun `guard refusal stops the call`() {
        every { operationGuardService.checkPermission("unpin_all_messages", any()) } throws
            ConfirmationRequiredException("unpin_all_messages", "Unpin all messages")

        val result = tool.executeChecked(exchange, mapOf("chat_id" to 42))

        assertTrue(result.isError)
        verify(exactly = 0) { telegramClient.unpinAllMessages(any()) }
    }
}
