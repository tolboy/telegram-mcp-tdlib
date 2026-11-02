package dev.telegrammcp.server.resource

import dev.telegrammcp.server.config.McpResourceProperties
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import kotlin.test.assertEquals

class ResourceCapabilitiesBuilderTest {
    private fun resources(properties: McpResourceProperties) =
        ResourceCapabilitiesBuilder(properties).resources(false, true).build().resources()

    @Test fun `disabled resources leave Spring AI's capability untouched`() {
        val caps = resources(McpResourceProperties())
        assertEquals(false, caps.subscribe())
        assertEquals(true, caps.listChanged())
    }

    @Test fun `enabled resources advertise subscribe and a static list`() {
        val caps = resources(McpResourceProperties(enabled = true))
        assertEquals(true, caps.subscribe())
        assertEquals(false, caps.listChanged())
        assertEquals(false, resources(McpResourceProperties(enabled = true, subscriptions = false)).subscribe())
    }

    @Test fun `limits are validated`() {
        assertThrows<IllegalArgumentException> { McpResourceProperties(debounce = Duration.ofMinutes(2)) }
        assertThrows<IllegalArgumentException> { McpResourceProperties(maxSubscriptionsPerSession = 0) }
    }
}
