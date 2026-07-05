package dev.telegrammcp.server.auth

import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TelegramAuthStateHolderTest {

    @Test
    fun `code challenge completes only the active pending request`() {
        val holder = TelegramAuthStateHolder()
        val pending = holder.awaitCode()
        holder.setState(AuthState.WaitingCode("+15550000000"))

        assertTrue(holder.submitCode("12345"))
        assertEquals("12345", pending.get(1, TimeUnit.SECONDS))
        assertFalse(holder.submitCode("second-attempt"))
    }

    @Test
    fun `password challenge completes without exposing the value in state`() {
        val holder = TelegramAuthStateHolder()
        val pending = holder.awaitPassword()
        holder.setState(AuthState.WaitingPassword("private hint"))

        assertTrue(holder.submitPassword("correct horse battery staple"))
        assertEquals("correct horse battery staple", pending.get(1, TimeUnit.SECONDS))
        assertTrue(holder.getState() is AuthState.WaitingPassword)
    }

    @Test
    fun `reset cancels pending challenges and returns to idle`() {
        val holder = TelegramAuthStateHolder()
        val code = holder.awaitCode()
        val password = holder.awaitPassword()

        holder.reset()

        assertTrue(code.isCancelled)
        assertTrue(password.isCancelled)
        assertTrue(holder.getState() is AuthState.Idle)
        assertFalse(holder.submitCode("12345"))
        assertFalse(holder.submitPassword("secret"))
    }
}
