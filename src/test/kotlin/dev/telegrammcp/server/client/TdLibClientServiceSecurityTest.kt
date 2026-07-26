package dev.telegrammcp.server.client

import dev.telegrammcp.server.exception.ChatNotAllowedException
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

class TdLibClientServiceSecurityTest {

    @Test
    fun `public join rejects a freshly resolved chat that differs from the validated id`() {
        assertFailsWith<ChatNotAllowedException> {
            TdLibClientService.requireExpectedPublicChatId(
                actualChatId = 200,
                expectedChatId = 100,
            )
        }
    }

    @Test
    fun `public join accepts the exact validated chat id`() {
        TdLibClientService.requireExpectedPublicChatId(
            actualChatId = 100,
            expectedChatId = 100,
        )
    }
}
