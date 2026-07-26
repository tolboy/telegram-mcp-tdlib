package dev.telegrammcp.server.service

import dev.telegrammcp.server.client.TelegramAccountContext
import dev.telegrammcp.server.client.TelegramAccountRegistry
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.config.AntiSpamProperties
import dev.telegrammcp.server.exception.AntiSpamException
import dev.telegrammcp.server.model.ParseMode
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AntiSpamNotifierTest {

    @Test
    fun `digest is sent only to internal chats allowed by Telegram policy`() {
        val fixture = notifierFixture(allowedChatIds = setOf(20L))
        try {
            fixture.queueThrottleEvent()

            fixture.notifier.dispatchAccountDigest("work")

            verify(exactly = 0) {
                fixture.telegramClient.sendMessage(10L, any(), ParseMode.PLAIN, null, null)
            }
            verify(exactly = 1) {
                fixture.telegramClient.sendMessage(20L, any(), ParseMode.PLAIN, null, null)
            }
            assertTrue(fixture.antiSpamGuardService.popPendingEvents("work", 10).isEmpty())
        } finally {
            fixture.notifier.stop()
        }
    }

    @Test
    fun `events stay queued when every internal notifier target is disallowed`() {
        val fixture = notifierFixture(allowedChatIds = emptySet())
        try {
            fixture.queueThrottleEvent()

            fixture.notifier.dispatchAccountDigest("work")

            verify(exactly = 0) {
                fixture.telegramClient.sendMessage(any(), any(), any(), any(), any())
            }
            val retained = fixture.antiSpamGuardService.popPendingEvents("work", 10)
            assertEquals(1, retained.size)
            assertEquals("create_channel", retained.single().toolName)
        } finally {
            fixture.notifier.stop()
        }
    }

    private fun notifierFixture(allowedChatIds: Set<Long>): NotifierFixture {
        val props = AntiSpamProperties(
            enabled = true,
            notifier = AntiSpamProperties.NotifierProps(
                enabled = true,
                maxEventsPerNotification = 20,
            ),
            overridesFile = null,
        )
        val telegramClient = mockk<TelegramClientService>(relaxed = true)
        val accountRegistry = TelegramAccountRegistry().also {
            it.register(TelegramAccountRegistry.AccountHandle("work", telegramClient))
        }
        val accountContext = TelegramAccountContext(accountRegistry)
        val antiSpamGuardService = AntiSpamGuardService(
            props = props,
            meterRegistry = SimpleMeterRegistry(),
            policy = AntiSpamPolicyService(props),
            accountContext = accountContext,
        )
        accountContext.withAccount("work") {
            antiSpamGuardService.registerInternalChat(10L)
            antiSpamGuardService.registerInternalChat(20L)
        }
        val guardrailService = mockk<GuardrailService> {
            every { isChatAllowed(any()) } answers { firstArg<Long>() in allowedChatIds }
        }
        val notifier = AntiSpamNotifier(
            antiSpamGuardService = antiSpamGuardService,
            guardrailService = guardrailService,
            accountRegistry = accountRegistry,
            accountContext = accountContext,
            props = props,
        )
        return NotifierFixture(
            notifier = notifier,
            antiSpamGuardService = antiSpamGuardService,
            accountContext = accountContext,
            telegramClient = telegramClient,
        )
    }

    private data class NotifierFixture(
        val notifier: AntiSpamNotifier,
        val antiSpamGuardService: AntiSpamGuardService,
        val accountContext: TelegramAccountContext,
        val telegramClient: TelegramClientService,
    ) {
        fun queueThrottleEvent() {
            accountContext.withAccount("work") {
                antiSpamGuardService.check("create_channel", mapOf("title" to "first"))
                assertThrows<AntiSpamException> {
                    antiSpamGuardService.check("create_channel", mapOf("title" to "second"))
                }
            }
        }
    }
}
