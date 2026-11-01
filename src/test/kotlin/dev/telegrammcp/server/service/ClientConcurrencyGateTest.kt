package dev.telegrammcp.server.service

import dev.telegrammcp.server.exception.TelegramBusyException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class ClientConcurrencyGateTest {

    @Test
    fun `a second in flight call from the same client is refused`() {
        val gate = ClientConcurrencyGate(1)
        gate.run {
            assertThrows<TelegramBusyException> {
                gate.run { }
            }
        }
    }

    @Test
    fun `the slot is released after the call returns`() {
        val gate = ClientConcurrencyGate(1)
        assertEquals("ok", gate.run { "ok" })
        assertEquals("again", gate.run { "again" })
    }
}
