package dev.telegrammcp.server.client

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import it.tdlight.client.GenericUpdateHandler
import it.tdlight.client.SimpleTelegramClientBuilder
import it.tdlight.jni.TdApi
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A connection state belongs to one client and is forgotten when that client goes away. */
class TelegramConnectionStatesTest {

    private val states = TelegramConnectionStates()

    private fun attachClient(account: String): GenericUpdateHandler<TdApi.UpdateConnectionState> {
        val builder = mockk<SimpleTelegramClientBuilder>(relaxed = true)
        val handler = slot<GenericUpdateHandler<TdApi.UpdateConnectionState>>()
        every { builder.addUpdateHandler(TdApi.UpdateConnectionState::class.java, capture(handler)) } returns Unit
        states.attach(builder, account)
        return handler.captured
    }

    private fun GenericUpdateHandler<TdApi.UpdateConnectionState>.report(state: TdApi.ConnectionState) =
        onUpdate(TdApi.UpdateConnectionState(state))

    @Test
    fun `clearing an account forgets its state and ignores the closed client's late updates`() {
        val client = attachClient("a")
        client.report(TdApi.ConnectionStateReady())
        assertTrue(states.isReachable("a"))

        states.clear("a")
        assertFalse(states.isReachable("a"))
        assertNull(states.stateName("a"))

        client.report(TdApi.ConnectionStateReady())
        assertFalse(states.isReachable("a"))
    }

    @Test
    fun `a recreated client starts without a state and the replaced client no longer reports`() {
        val old = attachClient("a")
        old.report(TdApi.ConnectionStateReady())

        val new = attachClient("a")
        assertNull(states.stateName("a"))
        old.report(TdApi.ConnectionStateReady())
        assertFalse(states.isReachable("a"))

        new.report(TdApi.ConnectionStateUpdating())
        assertEquals("ConnectionStateUpdating", states.stateName("a"))
        new.report(TdApi.ConnectionStateWaitingForNetwork())
        assertFalse(states.isReachable("a"))
    }

    @Test
    fun `accounts are independent`() {
        attachClient("a").report(TdApi.ConnectionStateReady())
        attachClient("b").report(TdApi.ConnectionStateReady())

        states.clear("a")
        assertFalse(states.isReachable("a"))
        assertTrue(states.isReachable("b"))
    }
}
