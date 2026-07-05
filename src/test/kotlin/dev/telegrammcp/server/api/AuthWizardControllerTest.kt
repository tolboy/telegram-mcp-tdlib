package dev.telegrammcp.server.api

import dev.telegrammcp.server.auth.AuthState
import dev.telegrammcp.server.auth.AuthWizardProperties
import dev.telegrammcp.server.auth.TelegramAuthStateHolder
import org.junit.jupiter.api.Test
import org.springframework.web.server.ResponseStatusException
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AuthWizardControllerTest {

    private val properties = AuthWizardProperties(
        enabled = true,
        nonce = "test-nonce",
        accountLabel = "work",
        method = "qr",
    )
    private val stateHolder = TelegramAuthStateHolder()
    private val controller = AuthWizardController(properties, stateHolder)

    @Test
    fun `setup embeds only loopback wizard state`() {
        val response = controller.setup("test-nonce")

        assertTrue(response.body!!.contains("Account label: <strong>work</strong>"))
        assertTrue(response.headers.cacheControl.orEmpty().contains("no-store"))
    }

    @Test
    fun `QR is rendered locally without exposing the link in HTML`() {
        stateHolder.setState(AuthState.WaitingQr("tg://login?token=sensitive"))

        val response = controller.qr("test-nonce")

        assertTrue(response.body!!.startsWith("<svg"))
        assertTrue(!response.body!!.contains("sensitive"))
    }

    @Test
    fun `wrong nonce is rejected`() {
        assertFailsWith<ResponseStatusException> {
            controller.setup("wrong")
        }
    }
}
