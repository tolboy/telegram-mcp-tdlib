package dev.telegrammcp.server.service

import dev.telegrammcp.server.client.McpCallCancellation
import dev.telegrammcp.server.client.McpCallCancelledException
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ElicitationExecutorTest {
    @Test
    fun `cancelling a local wait does not free capacity while its SDK prompt remains in flight`() {
        val requested = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = ElicitationExecutor(maxOutstanding = 1)
        try {
            val sdkRequest = executor.submit {
                requested.countDown()
                release.await(5, TimeUnit.SECONDS)
                "accepted"
            }
            assertTrue(requested.await(2, TimeUnit.SECONDS))
            McpCallCancellation.bind("cancel-approval")
            try {
                McpCallCancellation.cancel("cancel-approval")
                assertFailsWith<McpCallCancelledException> {
                    McpCallCancellation.await(sdkRequest, TimeUnit.SECONDS.toNanos(5))
                }
            } finally { McpCallCancellation.unbind() }
            assertFalse(sdkRequest.isDone)
            assertFailsWith<RejectedExecutionException> {
                executor.submit { error("Saturated work must not run on the caller") }
            }
            release.countDown()
            assertEquals("accepted", sdkRequest.get(2, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            executor.close()
        }
        assertFailsWith<RejectedExecutionException> { executor.submit { error("Closed executor must reject work") } }
    }
}
