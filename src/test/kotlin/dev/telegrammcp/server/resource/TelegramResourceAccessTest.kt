package dev.telegrammcp.server.resource

import dev.telegrammcp.server.config.ServerModeProperties
import dev.telegrammcp.server.exception.AccountAccessDeniedException
import dev.telegrammcp.server.security.AccessPermissionService
import dev.telegrammcp.server.security.AccountAccessPolicy
import dev.telegrammcp.server.service.GuardrailService
import dev.telegrammcp.server.service.ToolSurfacePolicy
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import java.time.Instant
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TelegramResourceAccessTest {
    private val accounts = mockk<AccountAccessPolicy>()
    private val surface = mockk<ToolSurfacePolicy>()
    private val guard = mockk<GuardrailService>()
    private val permissions = mockk<AccessPermissionService>()
    private val access = TelegramResourceAccess(accounts, surface, ServerModeProperties(), guard, permissions)

    init {
        every { surface.isVisible(any(), any(), any()) } returns true
        every { accounts.selectAccount(any()) } answers { firstArg<Map<String, Any>>()["account"].toString() }
        every { guard.isChatAllowed(any()) } answers { firstArg<Long>() == 42L }
        every { permissions.withPermission<Boolean>(any(), any(), any()) } answers { arg<() -> Boolean>(2)() }
    }

    @Test fun `chat resources follow the chat allow-list`() {
        assertTrue(access.permits(TelegramResourceUri.Chat("a", 42)))
        assertFalse(access.permits(TelegramResourceUri.Message("a", 43, 1)))
        assertTrue(access.permits(TelegramResourceUri.Chats("a")))
    }

    @Test fun `hidden tools, foreign accounts and permission failures deny`() {
        every { surface.isVisible("get_messages", any(), any()) } returns false
        assertFalse(access.permits(TelegramResourceUri.Message("a", 42, 1)))
        every { accounts.selectAccount(mapOf("account" to "b")) } throws AccountAccessDeniedException("no")
        assertFalse(access.permits(TelegramResourceUri.Chat("b", 42)))
        every { permissions.withPermission<Boolean>("list_chats", any(), any()) } throws IllegalStateException("denied")
        assertFalse(access.permits(TelegramResourceUri.Chats("a")))
    }

    @Test fun `stored identities are restored for the check and expired tokens deny`() {
        val alice = TestingAuthenticationToken("alice", null, "ROLE_X")
        var seen: String? = null
        every { guard.isChatAllowed(any()) } answers { seen = SecurityContextHolder.getContext().authentication?.name; true }
        assertTrue(access.permits(TelegramResourceUri.Chat("a", 42), alice))
        assertTrue(seen == "alice")
        assertNull(SecurityContextHolder.getContext().authentication)
        val jwt = Jwt.withTokenValue("t").header("alg", "none").subject("alice")
            .issuedAt(Instant.EPOCH).expiresAt(Instant.EPOCH.plusSeconds(60)).build()
        assertFalse(access.permits(TelegramResourceUri.Chat("a", 42), JwtAuthenticationToken(jwt, emptyList()), Instant.EPOCH.plusSeconds(61)))
    }
}
