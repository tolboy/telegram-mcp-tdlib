package dev.telegrammcp.server.client

import org.junit.jupiter.api.Test
import kotlin.test.assertContains

class TdLibGroupMemberResultTest {

    @Test
    fun `formats failed member additions without class cast wording`() {
        val message = formatFailedToAddMembers(
            operation = "add_chat_members",
            chatId = -100123L,
            failedMembers = listOf(
                FailedMemberAdd(
                    userId = 42L,
                    premiumWouldAllowInvite = true,
                    premiumRequiredToSendMessages = false,
                ),
            ),
        )

        assertContains(message, "Telegram rejected adding members")
        assertContains(message, "\"chat_id\":-100123")
        assertContains(message, "\"user_id\":42")
        assertContains(message, "premium_would_allow_invite")
    }
}
