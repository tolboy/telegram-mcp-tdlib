package dev.telegrammcp.server.cli

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TelegramMcpCliTest {

    @Test
    fun `CLI transport takes precedence over environment`() {
        val invocation = TelegramMcpCli.resolveServerInvocation(
            listOf("--transport", "stdio", "--spring.profiles.active=test"),
            "streamable-http",
        )

        assertEquals(TelegramMcpCli.Transport.STDIO, invocation.transport)
        assertEquals(listOf("--spring.profiles.active=test"), invocation.remaining)
    }

    @Test
    fun `environment selects stdio when CLI flag is absent`() {
        val invocation = TelegramMcpCli.resolveServerInvocation(emptyList(), "stdio")

        assertEquals(TelegramMcpCli.Transport.STDIO, invocation.transport)
    }

    @Test
    fun `legacy default stays streamable HTTP`() {
        val invocation = TelegramMcpCli.resolveServerInvocation(emptyList(), null)

        assertEquals(TelegramMcpCli.Transport.HTTP, invocation.transport)
    }

    @Test
    fun `unknown transport fails closed`() {
        assertFailsWith<IllegalStateException> {
            TelegramMcpCli.resolveServerInvocation(listOf("--transport=websocket"), null)
        }
    }
}
