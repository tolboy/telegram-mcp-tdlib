package dev.telegrammcp.server.service

import dev.telegrammcp.server.client.TelegramAccountContext
import dev.telegrammcp.server.client.TelegramAccountRegistry
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.exception.EntityNotFoundException
import dev.telegrammcp.server.model.EntityIdentifier
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class EntityResolverServiceTest {

    private lateinit var telegramClient: TelegramClientService
    private lateinit var service: EntityResolverService

    @BeforeEach
    fun setUp() {
        telegramClient = mockk()
        service = EntityResolverService(telegramClient)
    }

    @Nested
    inner class ResolveNumericId {

        @Test
        fun `resolves positive numeric ID directly`() {
            val result = service.resolve(EntityIdentifier.NumericId(123456789L))
            assertEquals(123456789L, result)
        }

        @Test
        fun `resolves negative numeric ID directly`() {
            val result = service.resolve(EntityIdentifier.NumericId(-1001234567890L))
            assertEquals(-1001234567890L, result)
        }

        @Test
        fun `resolves raw number`() {
            val result = service.resolve(42L as Any)
            assertEquals(42L, result)
        }

        @Test
        fun `resolves string-encoded number`() {
            val result = service.resolve("-1001234567890" as Any)
            assertEquals(-1001234567890L, result)
        }
    }

    @Nested
    inner class ResolveUsername {

        @Test
        fun `resolves username via TDLib`() {
            every { telegramClient.resolveUsername("channel_name") } returns 999L

            val result = service.resolve(EntityIdentifier.Username("channel_name"))
            assertEquals(999L, result)
            verify { telegramClient.resolveUsername("channel_name") }
        }

        @Test
        fun `caches resolved username`() {
            every { telegramClient.resolveUsername("test_user") } returns 123L

            // First call
            service.resolve(EntityIdentifier.Username("test_user"))
            // Second call should hit cache
            val result = service.resolve(EntityIdentifier.Username("test_user"))

            assertEquals(123L, result)
            verify(exactly = 1) { telegramClient.resolveUsername("test_user") }
        }

        @Test
        fun `throws EntityNotFoundException on resolution failure`() {
            every { telegramClient.resolveUsername("nonexistent") } throws RuntimeException("Not found")

            assertThrows<EntityNotFoundException> {
                service.resolve(EntityIdentifier.Username("nonexistent"))
            }
        }
    }

    @Nested
    inner class ResolvePhone {

        @Test
        fun `resolves phone number via TDLib`() {
            every { telegramClient.resolvePhone("+1234567890") } returns 456L

            val result = service.resolve(EntityIdentifier.PhoneNumber("+1234567890"))
            assertEquals(456L, result)
        }
    }

    @Nested
    inner class ResolveSelfChat {

        @Test
        fun `resolves self chat to saved messages chat id`() {
            every { telegramClient.resolveSelfChat() } returns 777L

            val result = service.resolve(EntityIdentifier.SelfChat("self"))

            assertEquals(777L, result)
            verify { telegramClient.resolveSelfChat() }
        }

        @Test
        fun `caches canonical self chat resolution`() {
            every { telegramClient.resolveSelfChat() } returns 777L

            service.resolve(EntityIdentifier.SelfChat("self"))
            val result = service.resolve(EntityIdentifier.SelfChat("self"))

            assertEquals(777L, result)
            verify(exactly = 1) { telegramClient.resolveSelfChat() }
        }
    }

    @Nested
    inner class MultiAccountIsolation {

        @Test
        fun `self chat cache never crosses account boundaries`() {
            val registry = TelegramAccountRegistry()
            registry.register(TelegramAccountRegistry.AccountHandle("personal", mockk<TelegramClientService>()))
            registry.register(TelegramAccountRegistry.AccountHandle("work", mockk<TelegramClientService>()))
            val accountContext = TelegramAccountContext(registry)
            val scopedService = EntityResolverService(telegramClient, accountContext)

            every { telegramClient.resolveSelfChat() } returns 111L
            val personal = accountContext.withAccount("personal") {
                scopedService.resolve(EntityIdentifier.SelfChat("self"))
            }

            every { telegramClient.resolveSelfChat() } returns 222L
            val work = accountContext.withAccount("work") {
                scopedService.resolve(EntityIdentifier.SelfChat("self"))
            }

            assertEquals(111L, personal)
            assertEquals(222L, work)
            verify(exactly = 2) { telegramClient.resolveSelfChat() }

            // Cached entries stay per-account on repeat resolution.
            val personalAgain = accountContext.withAccount("personal") {
                scopedService.resolve(EntityIdentifier.SelfChat("self"))
            }
            assertEquals(111L, personalAgain)
            verify(exactly = 2) { telegramClient.resolveSelfChat() }
        }

        @Test
        fun `username cache is scoped per account`() {
            val registry = TelegramAccountRegistry()
            registry.register(TelegramAccountRegistry.AccountHandle("personal", mockk<TelegramClientService>()))
            registry.register(TelegramAccountRegistry.AccountHandle("work", mockk<TelegramClientService>()))
            val accountContext = TelegramAccountContext(registry)
            val scopedService = EntityResolverService(telegramClient, accountContext)

            every { telegramClient.resolveUsername("shared_handle") } returns 42L
            accountContext.withAccount("personal") {
                scopedService.resolve(EntityIdentifier.Username("shared_handle"))
            }
            accountContext.withAccount("work") {
                scopedService.resolve(EntityIdentifier.Username("shared_handle"))
            }

            // Each account resolves through its own TDLib session.
            verify(exactly = 2) { telegramClient.resolveUsername("shared_handle") }
        }
    }

    @Nested
    inner class ParseAndResolve {

        @Test
        fun `resolves from raw string username`() {
            every { telegramClient.resolveUsername("test_channel") } returns 789L

            val result = service.resolve("@test_channel" as Any)
            assertEquals(789L, result)
        }

        @Test
        fun `resolves from raw integer`() {
            val result = service.resolve(42 as Any)
            assertEquals(42L, result)
        }

        @Test
        fun `resolves from raw canonical self identifier`() {
            every { telegramClient.resolveSelfChat() } returns 888L

            val result = service.resolve("self" as Any)

            assertEquals(888L, result)
        }
    }
}

