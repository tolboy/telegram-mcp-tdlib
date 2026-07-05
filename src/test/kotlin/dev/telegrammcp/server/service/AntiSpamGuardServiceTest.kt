package dev.telegrammcp.server.service

import dev.telegrammcp.server.config.AntiSpamProperties
import dev.telegrammcp.server.config.AntiSpamProperties.RuleProps
import dev.telegrammcp.server.client.TelegramAccountContext
import dev.telegrammcp.server.client.TelegramAccountRegistry
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.exception.AntiSpamException
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AntiSpamGuardServiceTest {

    private fun newGuard(
        rules: Map<String, RuleProps> = emptyMap(),
        internalChatIds: List<Long> = emptyList(),
        enabled: Boolean = true,
    ): AntiSpamGuardService {
        val props = AntiSpamProperties(
            enabled = enabled,
            rules = rules,
            internalChatIds = internalChatIds,
            overridesFile = null,
        )
        return AntiSpamGuardService(
            props,
            SimpleMeterRegistry(),
            AntiSpamPolicyService(props),
        )
    }

    @Test
    fun `disabled guard never throws`() {
        val guard = newGuard(enabled = false)
        repeat(100) {
            assertDoesNotThrow { guard.check("create_channel", emptyMap()) }
        }
    }

    @Test
    fun `built-in create_channel rule blocks the second call within a minute`() {
        val guard = newGuard()
        assertDoesNotThrow {
            guard.check("create_channel", mapOf("title" to "first"))
        }
        val ex = assertThrows<AntiSpamException> {
            guard.check("create_channel", mapOf("title" to "second"))
        }
        assertTrue(ex.message.contains("rate limit"))
    }

    @Test
    fun `external send_message rule applies tighter cap than built-in default`() {
        val guard = newGuard()
        // External chat (not in internalChatIds): default ext cap is 6/min.
        repeat(6) {
            guard.check("send_message", mapOf("chat_id" to 12345L, "text" to "msg-$it"))
        }
        assertThrows<AntiSpamException> {
            guard.check("send_message", mapOf("chat_id" to 12345L, "text" to "msg-7"))
        }
    }

    @Test
    fun `internal chat gets the looser limit`() {
        val guard = newGuard(internalChatIds = listOf(99L))
        // Internal cap for send_message is 30/min — well above 6.
        repeat(20) {
            guard.check("send_message", mapOf("chat_id" to 99L, "text" to "msg-$it"))
        }
    }

    @Test
    fun `rate limits are isolated between account labels`() {
        val props = AntiSpamProperties(overridesFile = null)
        val registry = TelegramAccountRegistry().also {
            it.register(TelegramAccountRegistry.AccountHandle("work", mockk<TelegramClientService>(relaxed = true)))
            it.register(TelegramAccountRegistry.AccountHandle("personal", mockk<TelegramClientService>(relaxed = true)))
        }
        val context = TelegramAccountContext(registry)
        val guard = AntiSpamGuardService(props, SimpleMeterRegistry(), AntiSpamPolicyService(props), context)

        context.withAccount("work") {
            repeat(6) { guard.check("send_message", mapOf("chat_id" to 77L, "text" to "work-$it")) }
            assertThrows<AntiSpamException> { guard.check("send_message", mapOf("chat_id" to 77L, "text" to "work-7")) }
        }
        context.withAccount("personal") {
            assertDoesNotThrow { guard.check("send_message", mapOf("chat_id" to 77L, "text" to "personal")) }
        }
    }

    @Test
    fun `register_internal_chat is exempt from anti-spam`() {
        val guard = newGuard()
        repeat(50) {
            guard.check("register_internal_chat", mapOf("chat_id" to 1L))
        }
    }

    @Test
    fun `read tools are exempt`() {
        val guard = newGuard()
        repeat(500) {
            guard.check("get_history", mapOf("chat_id" to 12345L))
            guard.check("list_chats", emptyMap())
        }
    }

    @Test
    fun `duplicate text within window is blocked`() {
        val guard = newGuard()
        guard.check("send_message", mapOf("chat_id" to 1L, "text" to "hello"))
        val ex = assertThrows<AntiSpamException> {
            guard.check("send_message", mapOf("chat_id" to 1L, "text" to "hello"))
        }
        assertTrue(ex.message.contains("duplicate") || ex.message.contains("rate limit"))
    }

    @Test
    fun `bypass disables checks for the current thread`() {
        val guard = newGuard()
        guard.withBypass {
            repeat(50) {
                guard.check("create_channel", mapOf("title" to "n-$it"))
            }
        }
    }

    @Test
    fun `throttled events are queued for digest notifier`() {
        val guard = newGuard()
        guard.check("create_channel", mapOf("title" to "first"))
        runCatching { guard.check("create_channel", mapOf("title" to "second")) }
        runCatching { guard.check("create_channel", mapOf("title" to "third")) }

        val events = guard.popPendingEvents(maxEvents = 10)
        assertEquals(2, events.size)
        assertTrue(events.all { it.toolName == "create_channel" })
    }

    @Test
    fun `register and isInternalChat round trip`() {
        val guard = newGuard()
        guard.registerInternalChat(42L)
        assertTrue(guard.isInternalChat(42L))
        assertTrue(42L in guard.internalChatIds())
    }

    @Test
    fun `custom rule from configuration overrides built-in`() {
        val guard = newGuard(
            rules = mapOf(
                "send_message" to RuleProps(maxOps = 1, windowMs = 60_000L),
            ),
        )
        guard.check("send_message", mapOf("chat_id" to 1L, "text" to "first"))
        assertThrows<AntiSpamException> {
            guard.check("send_message", mapOf("chat_id" to 1L, "text" to "second"))
        }
    }
}
