package dev.telegrammcp.server.tool.message

import dev.telegrammcp.server.client.TelegramAccountContext
import dev.telegrammcp.server.model.InlineButtonInfo
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InlineButtonTokensTest {
    private val buttons = listOf(
        InlineButtonInfo(row = 0, index = 0, text = "Approve", type = "callback", callbackData = "approve:7", position = 0, messageEditDate = 0),
        InlineButtonInfo(row = 0, index = 1, text = "Reject", type = "callback", callbackData = "reject:7", position = 1, messageEditDate = 0),
        InlineButtonInfo(row = 1, index = 0, text = "Docs", type = "url", url = "https://example.org", position = 2, messageEditDate = 0),
    )

    @Test
    fun `every listed button gets a distinct prefixed token that finds exactly that button`() {
        val tokens = InlineButtonTokens()
        val signed = tokens.sign(42, 100, buttons)
        assertEquals(buttons.size, signed.map { it.token }.toSet().size)
        signed.forEachIndexed { i, button ->
            val token = assertNotNull(button.token)
            assertTrue(token.startsWith(InlineButtonTokens.PREFIX) && token.length < 64, token)
            assertEquals(buttons[i], tokens.find(token, 42, 100, buttons))
        }
    }

    @Test
    fun `an edited message invalidates every token`() {
        val tokens = InlineButtonTokens()
        val token = tokens.sign(42, 100, buttons).first().token!!
        assertNull(tokens.find(token, 42, 100, buttons.map { it.copy(messageEditDate = 1_700_000_000) }))
    }

    @Test
    fun `a button whose data, text, type or place changed no longer matches`() {
        val tokens = InlineButtonTokens()
        val token = tokens.sign(42, 100, buttons).first().token!!
        val original = buttons.first()
        listOf(
            original.copy(callbackData = "reject:7"),
            original.copy(text = "Approve "),
            original.copy(type = "url"),
            original.copy(position = 1),
            original.copy(row = 1),
            original.copy(index = 1),
            original.copy(url = "https://evil.example"),
        ).forEach { changed -> assertNull(tokens.find(token, 42, 100, listOf(changed)), "$changed") }
        // Swapping two buttons does not let the token press the other one.
        val swapped = listOf(
            buttons[1].copy(position = 0, index = 0),
            buttons[0].copy(position = 1, index = 1),
        )
        assertNull(tokens.find(token, 42, 100, swapped))
    }

    @Test
    fun `tokens are bound to chat, message, account and process key`() {
        val tokens = InlineButtonTokens()
        val token = tokens.sign(42, 100, buttons).first().token!!
        assertNull(tokens.find(token, 43, 100, buttons))
        assertNull(tokens.find(token, 42, 101, buttons))
        assertNull(InlineButtonTokens().find(token, 42, 100, buttons), "a new key (restart) invalidates tokens")

        val accounts = mockk<TelegramAccountContext>()
        every { accounts.currentAccount() } returns "work"
        val scoped = InlineButtonTokens(accounts)
        val workToken = scoped.sign(42, 100, buttons).first().token!!
        every { accounts.currentAccount() } returns "personal"
        assertNull(scoped.find(workToken, 42, 100, buttons))
        assertNotEquals(workToken, scoped.sign(42, 100, buttons).first().token)
    }

    @Test
    fun `garbage and truncated tokens never match`() {
        val tokens = InlineButtonTokens()
        val token = tokens.sign(42, 100, buttons).first().token!!
        listOf("", "ibt1.", token.dropLast(1), token + "A", "ibt1.ÿÿÿ").forEach {
            assertNull(tokens.find(it, 42, 100, buttons), it)
        }
        assertEquals(buttons.first(), tokens.find(" $token ", 42, 100, buttons))
    }
}
