package dev.telegrammcp.server.tool.chat

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.model.BotCommand
import dev.telegrammcp.server.model.BotCommandScope
import dev.telegrammcp.server.model.BotCommandScopeKind
import dev.telegrammcp.server.model.ChatAdministratorRights
import dev.telegrammcp.server.model.ChatPermissions
import dev.telegrammcp.server.model.PrivacyRuleKind
import dev.telegrammcp.server.model.PrivacySetting
import dev.telegrammcp.server.model.PrivacySettingRules
import dev.telegrammcp.server.service.AuditService
import dev.telegrammcp.server.service.EntityResolverService
import dev.telegrammcp.server.service.GuardrailService
import dev.telegrammcp.server.service.OperationGuardService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.modelcontextprotocol.server.McpSyncServerExchange
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class P3SettingsToolsTest {

    private val telegramClient = mockk<TelegramClientService>()
    private val entityResolver = mockk<EntityResolverService>()
    private val guardrails = mockk<GuardrailService>(relaxed = true)
    private val operationGuard = mockk<OperationGuardService>(relaxed = true)
    private val audit = mockk<AuditService>(relaxed = true)
    private val exchange = mockk<McpSyncServerExchange>(relaxed = true)
    private val mapper = jacksonObjectMapper().findAndRegisterModules()

    @Test
    fun `privacy write builds a complete base policy with ordered exceptions`() {
        val tool = SetPrivacySettingsTool(telegramClient, entityResolver, guardrails, operationGuard, audit, mapper, SimpleMeterRegistry())
        val captured = slot<PrivacySettingRules>()
        every { entityResolver.resolve("@team" as Any) } returns -10042L
        every { telegramClient.setPrivacySettingRules(capture(captured)) } returns true

        val result = tool.execute(
            exchange,
            mapOf(
                "setting" to "show_status",
                "base_rule" to "allow_contacts",
                "allow_user_ids" to listOf(10, 11),
                "restrict_user_ids" to listOf(12),
                "allow_chat_ids" to listOf("@team"),
                "allow_bots" to false,
                "allow_premium_users" to true,
            ),
        )

        assertFalse(result.isError)
        assertEquals(PrivacySetting.SHOW_STATUS, captured.captured.setting)
        assertEquals(
            listOf(
                PrivacyRuleKind.RESTRICT_USERS,
                PrivacyRuleKind.ALLOW_USERS,
                PrivacyRuleKind.ALLOW_CHAT_MEMBERS,
                PrivacyRuleKind.RESTRICT_BOTS,
                PrivacyRuleKind.ALLOW_PREMIUM_USERS,
                PrivacyRuleKind.ALLOW_CONTACTS,
            ),
            captured.captured.rules.map { it.kind },
        )
        verify { operationGuard.checkPermission("set_privacy_settings", any()) }
        verify { guardrails.validateChatAccess(-10042L) }
    }

    @Test
    fun `group permission write preserves omitted flags`() {
        val tool = SetGroupPermissionsTool(telegramClient, entityResolver, guardrails, operationGuard, audit, mapper, SimpleMeterRegistry())
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { telegramClient.getChatPermissions(42L) } returns ChatPermissions(canSendBasicMessages = true, canSendPhotos = true)
        every { telegramClient.setChatPermissions(42L, any()) } returns true

        val result = tool.execute(
            exchange,
            mapOf("chat_id" to 42, "permissions" to mapOf("can_send_photos" to false), "confirmed" to true),
        )

        assertFalse(result.isError)
        verify { telegramClient.setChatPermissions(42L, ChatPermissions(canSendBasicMessages = true, canSendPhotos = false)) }
        verify { operationGuard.checkPermission("set_group_permissions", any()) }
    }

    @Test
    fun `bot command write resolves a chat member scope`() {
        val tool = SetBotCommandsTool(telegramClient, entityResolver, guardrails, operationGuard, audit, mapper, SimpleMeterRegistry())
        every { entityResolver.resolve("@team" as Any) } returns -10042L
        every { telegramClient.setBotCommands(any(), any(), any()) } returns true

        val result = tool.execute(
            exchange,
            mapOf(
                "scope" to "chat_member",
                "chat_id" to "@team",
                "user_id" to 55,
                "language_code" to "en",
                "commands" to listOf(mapOf("command" to "status", "description" to "Show status")),
            ),
        )

        assertFalse(result.isError)
        verify {
            telegramClient.setBotCommands(
                BotCommandScope(BotCommandScopeKind.CHAT_MEMBER, -10042L, 55L),
                "en",
                listOf(BotCommand("status", "Show status")),
            )
        }
    }

    @Test
    fun `administrator right write intentionally revokes omitted rights`() {
        val tool = SetAdminRightsTool(telegramClient, entityResolver, guardrails, operationGuard, audit, mapper, SimpleMeterRegistry())
        every { entityResolver.resolve(42 as Any) } returns 42L
        every { telegramClient.setChatMemberAdministratorRights(42L, 55L, any()) } returns true

        val result = tool.execute(
            exchange,
            mapOf("chat_id" to 42, "user_id" to 55, "rights" to mapOf("can_delete_messages" to true), "confirmed" to true),
        )

        assertFalse(result.isError)
        verify { telegramClient.setChatMemberAdministratorRights(42L, 55L, ChatAdministratorRights(canDeleteMessages = true)) }
        verify { operationGuard.checkPermission("set_admin_rights", any()) }
    }
}
