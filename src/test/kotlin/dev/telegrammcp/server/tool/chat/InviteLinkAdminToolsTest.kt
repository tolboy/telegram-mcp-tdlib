package dev.telegrammcp.server.tool.chat

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.model.ChatInviteLinkInfo
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

private fun linkInfo(revoked: Boolean = false) = ChatInviteLinkInfo(
    chatId = 42L,
    inviteLink = "https://t.me/+abc123",
    name = "main",
    creatorUserId = 7L,
    isPrimary = false,
    isRevoked = revoked,
    memberLimit = 0,
    memberCount = 3,
)

class ListInviteLinksToolTest {

    private lateinit var telegramClient: TelegramClientService
    private lateinit var entityResolver: EntityResolverService
    private lateinit var guardrailService: GuardrailService
    private lateinit var auditService: AuditService
    private lateinit var objectMapper: ObjectMapper
    private lateinit var tool: ListInviteLinksTool
    private lateinit var exchange: McpSyncServerExchange

    @BeforeEach
    fun setUp() {
        telegramClient = mockk()
        entityResolver = mockk()
        guardrailService = mockk(relaxed = true)
        auditService = mockk(relaxed = true)
        objectMapper = jacksonObjectMapper().findAndRegisterModules()
        exchange = mockk(relaxed = true)

        tool = ListInviteLinksTool(
            telegramClient = telegramClient,
            entityResolver = entityResolver,
            guardrailService = guardrailService,
            auditService = auditService,
            objectMapper = objectMapper,
            meterRegistry = SimpleMeterRegistry(),
        )
    }

    @Test
    fun `definition returns correct tool name`() {
        assertEquals("list_invite_links", tool.definition().name())
    }

    @Test
    fun `lists active invite links by default`() {
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { telegramClient.getChatInviteLinks(42L, false, 20) } returns listOf(linkInfo())

        val result = tool.execute(exchange, mapOf("chat_id" to 42))

        assertFalse(result.isError)
        val text = (result.content().first() as McpSchema.TextContent).text()
        assertTrue("abc123" in text)
        verify { telegramClient.getChatInviteLinks(42L, false, 20) }
    }

    @Test
    fun `lists revoked links when requested`() {
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { telegramClient.getChatInviteLinks(42L, true, 5) } returns listOf(linkInfo(revoked = true))

        val result = tool.execute(exchange, mapOf("chat_id" to 42, "include_revoked" to true, "limit" to 5))

        assertFalse(result.isError)
        verify { telegramClient.getChatInviteLinks(42L, true, 5) }
    }
}

class RevokeInviteLinkToolTest {

    private lateinit var telegramClient: TelegramClientService
    private lateinit var entityResolver: EntityResolverService
    private lateinit var guardrailService: GuardrailService
    private lateinit var operationGuardService: OperationGuardService
    private lateinit var auditService: AuditService
    private lateinit var objectMapper: ObjectMapper
    private lateinit var tool: RevokeInviteLinkTool
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

        tool = RevokeInviteLinkTool(
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
        assertEquals("revoke_invite_link", tool.definition().name())
    }

    @Test
    fun `revokes the given link`() {
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { telegramClient.revokeChatInviteLink(42L, "https://t.me/+abc123") } returns listOf(linkInfo(revoked = true))

        val result = tool.execute(
            exchange,
            mapOf("chat_id" to 42, "invite_link" to "https://t.me/+abc123", "confirmed" to true),
        )

        assertFalse(result.isError)
        verify { telegramClient.revokeChatInviteLink(42L, "https://t.me/+abc123") }
        verify { operationGuardService.checkPermission("revoke_invite_link", any()) }
    }

    @Test
    fun `requires invite_link`() {
        every { entityResolver.resolve(42 as Any) } returns 42L

        val result = tool.execute(exchange, mapOf("chat_id" to 42, "confirmed" to true))

        assertTrue(result.isError)
        verify(exactly = 0) { telegramClient.revokeChatInviteLink(any(), any()) }
    }
}
