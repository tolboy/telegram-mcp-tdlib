package dev.telegrammcp.server.config

import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
}
