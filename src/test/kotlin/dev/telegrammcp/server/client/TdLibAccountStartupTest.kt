package dev.telegrammcp.server.client

import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TdLibAccountStartupTest {
    @Test
    fun `a later account startup failure closes earlier clients and restores fallback`() {
        val registry = TelegramAccountRegistry()
        val fallback = mockk<TelegramClientService>()
        val delegating = DelegatingTelegramClientService.wrapping(fallback)
        val closed = AtomicInteger()
        val unavailable = IllegalStateException("second account failed")
        val error = assertFailsWith<IllegalStateException> {
            registerAccountClients(registry, delegating, listOf(
                { TelegramAccountRegistry.AccountHandle("default", mockk()) { closed.incrementAndGet() } },
                { throw unavailable },
            ))
        }
        assertSame(unavailable, error)
        assertEquals(1, closed.get())
        assertTrue(registry.labels().isEmpty())
        assertSame(fallback, delegating.current())
    }

    @Test
    fun `failed registration closes the unregistered client and all registered clients`() {
        val registry = TelegramAccountRegistry()
        val fallback = mockk<TelegramClientService>()
        val delegating = DelegatingTelegramClientService.wrapping(fallback)
        val firstClosed = AtomicInteger()
        val duplicateClosed = AtomicInteger()
        assertFailsWith<IllegalArgumentException> {
            registerAccountClients(registry, delegating, listOf(
                { TelegramAccountRegistry.AccountHandle("work", mockk()) { firstClosed.incrementAndGet() } },
                { TelegramAccountRegistry.AccountHandle("work", mockk()) { duplicateClosed.incrementAndGet() } },
            ))
        }
        assertEquals(1, firstClosed.get())
        assertEquals(1, duplicateClosed.get())
        assertTrue(registry.labels().isEmpty())
    }
}
