package dev.telegrammcp.server.client

import dev.telegrammcp.server.model.UserInfo
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class TelegramAccountRegistryTest {

    @Test
    fun `routes each invocation to the selected isolated account`() {
        val work = mockk<TelegramClientService>()
        val personal = mockk<TelegramClientService>()
        every { work.getMe() } returns UserInfo(1, "Work")
        every { personal.getMe() } returns UserInfo(2, "Personal")

        val registry = TelegramAccountRegistry().also {
            it.register(TelegramAccountRegistry.AccountHandle("work", work))
            it.register(TelegramAccountRegistry.AccountHandle("personal", personal))
        }
        val context = TelegramAccountContext(registry)
        val routed = AccountRoutingTelegramClientService(registry, context).proxy

        assertEquals(1, context.withAccount("work") { routed.getMe().userId })
        assertEquals(2, context.withAccount("personal") { routed.getMe().userId })
    }

    @Test
    fun `account labels reject unsafe identifiers`() {
        assertThrows<IllegalArgumentException> { TelegramAccountRegistry.normalizeLabel("../personal") }
        assertThrows<IllegalArgumentException> { TelegramAccountRegistry.normalizeLabel("personal account") }
    }

    @Test
    fun `routing cannot happen without a request account context`() {
        val registry = TelegramAccountRegistry().also {
            it.register(TelegramAccountRegistry.AccountHandle("work", mockk(relaxed = true)))
        }
        val routed = AccountRoutingTelegramClientService(registry, TelegramAccountContext(registry)).proxy

        assertThrows<RuntimeException> { routed.getMe() }
    }
}
