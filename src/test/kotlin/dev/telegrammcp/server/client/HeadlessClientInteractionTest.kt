package dev.telegrammcp.server.client

import dev.telegrammcp.server.exception.TdLibAuthException
import dev.telegrammcp.server.util.StructuredLogger
import io.mockk.mockk
import it.tdlight.client.InputParameter
import it.tdlight.client.ParameterInfo
import org.junit.jupiter.api.Test
import java.util.concurrent.ExecutionException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The STDIO transport reserves stdout for JSON-RPC frames and stdin for the
 * transport reader. TDLib parameter requests must therefore be answered from
 * configuration or fail authentication — never fall back to console prompts.
 */
class HeadlessClientInteractionTest {

    private val log = StructuredLogger.forClass<HeadlessClientInteractionTest>()
    private val info = mockk<ParameterInfo>(relaxed = true)

    private fun interaction(
        code: String = "",
        password: String = "",
        allowConsolePrompts: Boolean = false,
    ) = HeadlessClientInteraction(
        label = "default",
        configuredCode = code,
        configuredPassword = password,
        allowConsolePrompts = allowConsolePrompts,
        log = log,
    )

    @Test
    fun `configured code and password are answered without touching the console`() {
        val interaction = interaction(code = "12345", password = "hunter2")

        assertEquals("12345", interaction.onParameterRequest(InputParameter.ASK_CODE, info).get())
        assertEquals("hunter2", interaction.onParameterRequest(InputParameter.ASK_PASSWORD, info).get())
    }

    @Test
    fun `unresolved parameter fails authentication instead of prompting when prompts are disallowed`() {
        val interaction = interaction()

        val future = interaction.onParameterRequest(InputParameter.ASK_CODE, info)

        assertTrue(future.isCompletedExceptionally)
        val cause = assertFailsWith<ExecutionException> { future.get() }.cause
        assertTrue(cause is TdLibAuthException)
        assertTrue(cause.message.orEmpty().contains("STDIO"))
    }

    @Test
    fun `a configured login code is used at most once`() {
        val interaction = interaction(code = "12345")

        assertEquals("12345", interaction.onParameterRequest(InputParameter.ASK_CODE, info).get())
        // A second code request means the first code was wrong; re-sending it
        // would loop forever, so it must fail like an unconfigured parameter.
        assertTrue(interaction.onParameterRequest(InputParameter.ASK_CODE, info).isCompletedExceptionally)
    }

    @Test
    fun `terms of service and notify-link never require a console`() {
        val interaction = interaction()

        assertEquals("Y", interaction.onParameterRequest(InputParameter.TERMS_OF_SERVICE, info).get())
        assertEquals("", interaction.onParameterRequest(InputParameter.NOTIFY_LINK, info).get())
    }
}
