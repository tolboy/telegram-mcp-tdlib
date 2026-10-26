package dev.telegrammcp.server.config

import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TdLibRequestsPropertiesTest {
    private fun bind(values: Map<String, String>) =
        Binder(MapConfigurationPropertySource(values)).bindOrCreate("tdlib.requests", TdLibProperties.Requests::class.java)

    @Test
    fun `request timeouts and limits are configurable`() {
        val bound = bind(mapOf(
            "tdlib.requests.timeout" to "20s",
            "tdlib.requests.message-send-timeout" to "25s",
            "tdlib.requests.download-timeout" to "2m",
            "tdlib.requests.tool-deadline" to "30s",
            "tdlib.requests.local-timeout-gate" to "0s",
            "tdlib.requests.max-concurrent-calls" to "4",
        ))
        assertEquals(Duration.ofSeconds(20), bound.timeout)
        assertEquals(Duration.ofSeconds(25), bound.messageSendTimeout)
        assertEquals(Duration.ofMinutes(2), bound.downloadTimeout)
        assertEquals(Duration.ofSeconds(30), bound.toolDeadline)
        assertEquals(Duration.ZERO, bound.localTimeoutGate)
        assertEquals(4, bound.maxConcurrentCalls)
        assertEquals(TdLibProperties.Requests(), bind(emptyMap()))
    }

    @Test
    fun `invalid values are rejected`() {
        assertFailsWith<Exception> { bind(mapOf("tdlib.requests.timeout" to "0s")) }
        assertFailsWith<Exception> { bind(mapOf("tdlib.requests.tool-deadline" to "-1s")) }
        assertFailsWith<Exception> { bind(mapOf("tdlib.requests.max-concurrent-calls" to "0")) }
    }

    @Test
    fun `the tool deadline must be longer than the request and send timeouts`() {
        for (values in listOf(
            mapOf("tdlib.requests.tool-deadline" to "40s"),
            mapOf("tdlib.requests.tool-deadline" to "30s", "tdlib.requests.message-send-timeout" to "20s"),
            mapOf("tdlib.requests.timeout" to "20s", "tdlib.requests.message-send-timeout" to "55s"),
        )) {
            val error = assertFailsWith<Exception>("$values") { bind(values) }
            val message = generateSequence<Throwable>(error) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
            assertTrue("tdlib.requests.tool-deadline" in message && "message-send-timeout" in message, message)
        }
        val direct = assertFailsWith<IllegalArgumentException> {
            TdLibProperties.Requests(timeout = Duration.ofSeconds(60))
        }
        assertEquals(
            "tdlib.requests.tool-deadline (PT50S) must be longer than tdlib.requests.timeout (PT1M) and " +
                "tdlib.requests.message-send-timeout (PT40S); raise TDLIB_TOOL_DEADLINE or lower " +
                "TDLIB_REQUEST_TIMEOUT / TDLIB_MESSAGE_SEND_TIMEOUT",
            direct.message,
        )
        TdLibProperties.Requests(timeout = Duration.ofSeconds(20), messageSendTimeout = Duration.ofSeconds(20), toolDeadline = Duration.ofSeconds(21))
    }
}
