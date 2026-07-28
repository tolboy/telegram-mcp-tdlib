package dev.telegrammcp.server.service

import org.junit.jupiter.api.Test
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The loopback route is what makes approval usable at all on clients that never
 * implemented elicitation, so its guarantees are load-bearing: only a person on
 * this machine can answer, a link answers exactly one operation, and silence
 * refuses rather than waits forever.
 */
class LoopbackApprovalServerTest {

    /** Captures the announced link so a test can act as the operator. */
    private class Announcer {
        private val announced = CompletableFuture<String>()
        val sink: (String) -> Unit = { line -> announced.complete(line) }
        fun url(): String {
            val line = announced.get(10, TimeUnit.SECONDS)
            return Regex("http://127\\.0\\.0\\.1:\\d+/approve\\?[^\\s]+").find(line)?.value
                ?: error("no approval URL was announced in: $line")
        }
    }

    private fun post(url: String, decision: String): Int = request(url, "POST", "decision=$decision")

    private fun get(url: String): Int = request(url, "GET", null)

    private fun request(url: String, method: String, body: String?): Int {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        if (body != null) {
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
        }
        return try {
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }

    /** Runs [operatorAction] once the link appears, while the caller blocks. */
    private fun withPendingApproval(
        server: LoopbackApprovalServer,
        announcer: Announcer,
        operatorAction: (String) -> Unit,
    ): Boolean {
        val result = CompletableFuture.supplyAsync {
            server.requestApproval("ban_user", "chat_id=-100, user_id=42")
        }
        operatorAction(announcer.url())
        return result.get(20, TimeUnit.SECONDS)
    }

    @Test
    fun `approving on the page lets the operation proceed`() {
        val announcer = Announcer()
        LoopbackApprovalServer(Duration.ofSeconds(15), announcer.sink).use { server ->
            val approved = withPendingApproval(server, announcer) { url ->
                assertEquals(200, get(url), "the operator must be able to open the page")
                assertEquals(200, post(url, "approve"))
            }
            assertTrue(approved)
        }
    }

    @Test
    fun `denying on the page refuses the operation`() {
        val announcer = Announcer()
        LoopbackApprovalServer(Duration.ofSeconds(15), announcer.sink).use { server ->
            val approved = withPendingApproval(server, announcer) { url -> post(url, "deny") }
            assertFalse(approved)
        }
    }

    /** Silence is the common case when nobody is watching, and it must not pass. */
    @Test
    fun `an unanswered request expires as a refusal`() {
        val announcer = Announcer()
        LoopbackApprovalServer(Duration.ofSeconds(1), announcer.sink).use { server ->
            assertFalse(server.requestApproval("delete_message", "chat_id=1, message_id=2"))
        }
    }

    /**
     * A link is one decision about one operation. Without single use, an
     * approval for a harmless call could be replayed against a later one.
     */
    @Test
    fun `a link cannot answer a second operation`() {
        val announcer = Announcer()
        LoopbackApprovalServer(Duration.ofSeconds(15), announcer.sink).use { server ->
            val url = withPendingApprovalReturningUrl(server, announcer)
            assertEquals(404, post(url, "approve"), "a spent link must no longer resolve")
            assertEquals(404, get(url), "a spent link must not render the page again")
        }
    }

    private fun withPendingApprovalReturningUrl(
        server: LoopbackApprovalServer,
        announcer: Announcer,
    ): String {
        val result = CompletableFuture.supplyAsync {
            server.requestApproval("ban_user", "chat_id=-100, user_id=42")
        }
        val url = announcer.url()
        post(url, "approve")
        assertTrue(result.get(20, TimeUnit.SECONDS))
        return url
    }

    @Test
    fun `a wrong nonce is refused and reveals nothing`() {
        val announcer = Announcer()
        LoopbackApprovalServer(Duration.ofSeconds(15), announcer.sink).use { server ->
            val started = CountDownLatch(1)
            val result = CompletableFuture.supplyAsync {
                started.countDown()
                server.requestApproval("leave_chat", "chat_id=7")
            }
            started.await(5, TimeUnit.SECONDS)
            val url = announcer.url()
            val tampered = url.replace(Regex("nonce=[^&]+"), "nonce=guessed")
            assertEquals(404, get(tampered), "a wrong nonce must not open the page")
            assertEquals(404, post(tampered, "approve"), "a wrong nonce must not approve")

            // The real link still works, so the rejection did not consume it.
            post(url, "deny")
            assertFalse(result.get(20, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `an unknown id is refused`() {
        val announcer = Announcer()
        LoopbackApprovalServer(Duration.ofSeconds(15), announcer.sink).use { server ->
            val result = CompletableFuture.supplyAsync {
                server.requestApproval("ban_user", "chat_id=1, user_id=2")
            }
            val url = announcer.url()
            val port = requireNotNull(server.boundPort())
            assertEquals(404, get("http://127.0.0.1:$port/approve?id=made-up&nonce=made-up"))
            post(url, "deny")
            assertFalse(result.get(20, TimeUnit.SECONDS))
        }
    }

    /** Shutdown must release a waiting caller rather than hang, and refuse it. */
    @Test
    fun `closing the server refuses an in-flight request`() {
        val announcer = Announcer()
        val server = LoopbackApprovalServer(Duration.ofSeconds(30), announcer.sink)
        val result = CompletableFuture.supplyAsync {
            server.requestApproval("ban_user", "chat_id=1, user_id=2")
        }
        announcer.url()
        server.close()
        assertFalse(result.get(20, TimeUnit.SECONDS), "an unanswered request must not become an approval")
    }

    /** The operator decides from this page, and message text can be hostile input. */
    @Test
    fun `the page escapes the description it renders`() {
        val announcer = Announcer()
        LoopbackApprovalServer(Duration.ofSeconds(15), announcer.sink).use { server ->
            val result = CompletableFuture.supplyAsync {
                server.requestApproval("ban_user", "chat_id=<script>alert(1)</script>")
            }
            val url = announcer.url()
            val body = URI(url).toURL().openStream().use { it.readBytes().toString(StandardCharsets.UTF_8) }
            assertFalse("<script>alert(1)</script>" in body, "raw markup must not reach the page")
            assertTrue("&lt;script&gt;" in body, "the value must still be shown, escaped")
            post(url, "deny")
            result.get(20, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `the listener binds only to loopback`() {
        val announcer = Announcer()
        LoopbackApprovalServer(Duration.ofSeconds(5), announcer.sink).use { server ->
            CompletableFuture.supplyAsync { server.requestApproval("ban_user", "chat_id=1") }
            announcer.url()
            assertTrue(announcer.url().startsWith("http://127.0.0.1:"), "the link must be loopback-only")
        }
    }
}
