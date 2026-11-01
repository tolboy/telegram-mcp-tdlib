package dev.telegrammcp.server.tool

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class McpProgressTest {
    @Test fun `progress tokens accept only protocol strings and finite numbers`() {
        for (value in listOf(true, emptyMap<String, String>(), listOf(1), Double.NaN, Double.POSITIVE_INFINITY)) {
            assertNull(McpProgress.token(mapOf("progressToken" to value)))
        }
        for (value in listOf("", "request-1", 0L, 1.5)) {
            assertEquals(value, McpProgress.token(mapOf("progressToken" to value)))
        }
    }
}
