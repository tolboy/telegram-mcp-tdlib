package dev.telegrammcp.server.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpCallCancellationTest {
    @Test
    fun `cancellation between filter registration and tool listener is preserved`() {
        McpCallCancellation.bind("early-cancel")
        try {
            McpCallCancellation.cancel("early-cancel")
            McpCallCancellation.listen { assertTrue(McpCallCancellation.isSignalled()) }
            assertTrue(McpCallCancellation.isSignalled(), "Nested listener cleanup must not erase the request signal")
        } finally { McpCallCancellation.unbind() }
        assertFalse(McpCallCancellation.isSignalled())
        McpCallCancellation.bind("early-cancel")
        try { assertFalse(McpCallCancellation.isSignalled(), "A later request starts with a fresh signal") }
        finally { McpCallCancellation.unbind() }
    }

    @Test
    fun `research worker coroutines observe cancellation without cancelling the Telegram future`() {
        val future = CompletableFuture<Unit>()
        McpCallCancellation.bind("research-cancel")
        try {
            val context = McpCallCancellation.coroutineContext()
            McpCallCancellation.cancel("research-cancel")
            runBlocking(context) {
                withContext(Dispatchers.IO) {
                    assertFailsWith<McpCallCancelledException> {
                        McpCallCancellation.await(future, TimeUnit.SECONDS.toNanos(1))
                    }
                }
            }
            assertFalse(future.isDone)
        } finally { McpCallCancellation.unbind() }
    }
}
