package dev.telegrammcp.server.service

import com.sun.net.httpserver.HttpServer
import dev.telegrammcp.server.client.McpCallCancellation
import dev.telegrammcp.server.exception.TelegramTimeoutException
import dev.telegrammcp.server.service.LoopbackApprovalServer.ApprovalResult
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The loopback route is what makes approval usable at all on clients that never
 * implemented elicitation, so its guarantees are load-bearing: only a person on
 * this machine can answer, a link answers exactly one operation, and silence
 * refuses rather than waits forever.
 */
class LoopbackApprovalServerTest {

    @Test
    fun `mcp cancellation stops the approval wait without recording a denial`() {
        val server = LoopbackApprovalServer(
            Duration.ofSeconds(30),
            announce = { _ -> },
            printLink = true,
            openBrowser = { false },
        )
        val key = "approval-wait"
        val result = CompletableFuture<Any>()
        val worker = Thread({
            McpCallCancellation.bind(key)
            try {
                result.complete(server.requestApproval("ban_user", "chat_id=1"))
            } catch (error: Throwable) {
                result.complete(error)
            } finally {
                McpCallCancellation.unbind()
            }
        }, "approval-cancel-test").apply { isDaemon = true; start() }
        val started = System.nanoTime()
        while (worker.state != Thread.State.TIMED_WAITING) {
            assertTrue(worker.isAlive, "approval wait ended before it blocked")
            assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(3))
            Thread.yield()
        }
        McpCallCancellation.cancel(key)
        val outcome = result.get(2, TimeUnit.SECONDS)
        assertTrue(outcome is TelegramTimeoutException, outcome.toString())
        val timeout = outcome as TelegramTimeoutException
        assertEquals(TelegramTimeoutException.Reason.LOCAL_CANCEL, timeout.reason)
        assertFalse(timeout.healthFailure)
        server.close()
        worker.join(1_000)
    }

    @Test
    fun `approval timeout must be positive`() {
        listOf(Duration.ZERO, Duration.ofSeconds(-1)).forEach { invalid ->
            assertFailsWith<IllegalArgumentException> {
                LoopbackApprovalServer(invalid)
            }
        }
    }

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

    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    /** What the operator's browser does: load the page, then submit its form. */
    private fun post(url: String, decision: String): Int {
        val token = formToken(url) ?: return request(url, "POST", "decision=$decision")
        return request(url, "POST", "form_token=$token&decision=$decision")
    }

    private fun formToken(url: String): String? {
        val response = send(url, "GET", null, emptyMap())
        if (response.statusCode() != 200) return null
        return Regex("name=\"form_token\" value=\"([^\"]+)\"").find(response.body())?.groupValues?.get(1)
    }

    private fun get(url: String): Int = request(url, "GET", null)

    private fun request(
        url: String,
        method: String,
        body: String?,
        headers: Map<String, String> = if (method == "POST") mapOf("Origin" to originOf(url)) else emptyMap(),
    ): Int = send(url, method, body, headers).statusCode()

    private fun send(url: String, method: String, body: String?, headers: Map<String, String>): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(5))
            .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
        headers.forEach { (name, value) -> builder.header(name, value) }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun originOf(url: String): String = URI(url).let { "${it.scheme}://${it.host}:${it.port}" }

    /** A server that hands the link to [announcer] the way a watched terminal would. */
    private fun printingServer(
        timeout: Duration,
        announcer: Announcer,
        serverFactory: ((InetSocketAddress, Int) -> HttpServer)? = null,
    ): LoopbackApprovalServer = if (serverFactory == null) {
        LoopbackApprovalServer(timeout, announcer.sink, printLink = true)
    } else {
        LoopbackApprovalServer(timeout, announcer.sink, serverFactory, printLink = true)
    }

    /** Runs [operatorAction] once the link appears, while the caller blocks. */
    private fun withPendingApproval(
        server: LoopbackApprovalServer,
        announcer: Announcer,
        operatorAction: (String) -> Unit,
    ): ApprovalResult {
        val result = CompletableFuture.supplyAsync {
            server.requestApproval("ban_user", "chat_id=-100, user_id=42")
        }
        operatorAction(announcer.url())
        return result.get(20, TimeUnit.SECONDS)
    }

    @Test
    fun `approving on the page lets the operation proceed`() {
        val announcer = Announcer()
        printingServer(Duration.ofSeconds(15), announcer).use { server ->
            val result = withPendingApproval(server, announcer) { url ->
                assertEquals(200, get(url), "the operator must be able to open the page")
                assertEquals(200, post(url, "approve"))
            }
            assertEquals(ApprovalResult.APPROVED, result)
        }
    }

    @Test
    fun `denying on the page refuses the operation`() {
        val announcer = Announcer()
        printingServer(Duration.ofSeconds(15), announcer).use { server ->
            val result = withPendingApproval(server, announcer) { url -> post(url, "deny") }
            assertEquals(ApprovalResult.DENIED, result)
        }
    }

    /** Silence is the common case when nobody is watching, and it must not pass. */
    @Test
    fun `an unanswered request expires as a refusal`() {
        val announcer = Announcer()
        printingServer(Duration.ofSeconds(1), announcer).use { server ->
            assertEquals(
                ApprovalResult.TIMED_OUT,
                server.requestApproval("delete_message", "chat_id=1, message_id=2"),
            )
        }
    }

    /**
     * A link is one decision about one operation. Without single use, an
     * approval for a harmless call could be replayed against a later one.
     */
    @Test
    fun `a link cannot answer a second operation`() {
        val announcer = Announcer()
        printingServer(Duration.ofSeconds(15), announcer).use { server ->
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
        assertEquals(ApprovalResult.APPROVED, result.get(20, TimeUnit.SECONDS))
        return url
    }

    @Test
    fun `a wrong nonce is refused and reveals nothing`() {
        val announcer = Announcer()
        printingServer(Duration.ofSeconds(15), announcer).use { server ->
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
            assertEquals(ApprovalResult.DENIED, result.get(20, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `an unknown id is refused`() {
        val announcer = Announcer()
        printingServer(Duration.ofSeconds(15), announcer).use { server ->
            val result = CompletableFuture.supplyAsync {
                server.requestApproval("ban_user", "chat_id=1, user_id=2")
            }
            val url = announcer.url()
            val port = requireNotNull(server.boundPort())
            assertEquals(404, get("http://127.0.0.1:$port/approve?id=made-up&nonce=made-up"))
            post(url, "deny")
            assertEquals(ApprovalResult.DENIED, result.get(20, TimeUnit.SECONDS))
        }
    }

    /** Shutdown must release a waiting caller rather than hang, and refuse it. */
    @Test
    fun `closing the server refuses an in-flight request`() {
        val announcer = Announcer()
        val factoryCalls = AtomicInteger()
        val server = printingServer(
            Duration.ofSeconds(30),
            announcer,
            serverFactory = { address, backlog ->
                factoryCalls.incrementAndGet()
                HttpServer.create(address, backlog)
            },
        )
        val result = CompletableFuture.supplyAsync {
            server.requestApproval("ban_user", "chat_id=1, user_id=2")
        }
        announcer.url()
        server.close()
        assertEquals(
            ApprovalResult.UNAVAILABLE,
            result.get(20, TimeUnit.SECONDS),
            "an unanswered request must not become an approval",
        )
        assertEquals(
            ApprovalResult.UNAVAILABLE,
            server.requestApproval("ban_user", "chat_id=3, user_id=4"),
            "close must remain terminal after a listener was started",
        )
        assertEquals(1, factoryCalls.get(), "a closed server must not recreate its listener")
    }

    @Test
    fun `a request after close is refused immediately without restarting the listener`() {
        val factoryCalls = AtomicInteger()
        val server = LoopbackApprovalServer(
            Duration.ofSeconds(30),
            serverFactory = { address, backlog ->
                factoryCalls.incrementAndGet()
                HttpServer.create(address, backlog)
            },
        )
        server.close()

        val startedAt = System.nanoTime()
        assertEquals(
            ApprovalResult.UNAVAILABLE,
            server.requestApproval("ban_user", "chat_id=1, user_id=2"),
        )
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertTrue(elapsedMs < 1_000, "closed requests must not wait for the approval timeout: ${elapsedMs}ms")
        assertEquals(0, factoryCalls.get(), "close is terminal; it must not start a new listener")
    }

    @Test
    fun `a listener start failure is surfaced without spinning`() {
        val server = LoopbackApprovalServer(
            Duration.ofSeconds(30),
            serverFactory = { _, _ -> throw IOException("synthetic bind failure") },
        )

        val startedAt = System.nanoTime()
        val error = assertFailsWith<ApprovalEndpointException> {
            server.requestApproval("ban_user", "chat_id=1, user_id=2")
        }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertTrue("synthetic bind failure" in error.message.orEmpty())
        assertTrue(elapsedMs < 1_000, "a failed bind must not leave callers spinning: ${elapsedMs}ms")
        server.close()
    }

    /** The operator decides from this page, and message text can be hostile input. */
    @Test
    fun `the page escapes the description it renders`() {
        val announcer = Announcer()
        printingServer(Duration.ofSeconds(15), announcer).use { server ->
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

    /** The confirmation page renders the same values back; so must it escape them. */
    @Test
    fun `the result page escapes what it echoes`() {
        val announcer = Announcer()
        printingServer(Duration.ofSeconds(15), announcer).use { server ->
            val result = CompletableFuture.supplyAsync {
                server.requestApproval("<img src=x onerror=alert(1)>", "chat_id=1")
            }
            val url = announcer.url()
            val token = formToken(url)
            val body = send(url, "POST", "form_token=$token&decision=deny", mapOf("Origin" to originOf(url))).body()

            assertFalse("<img src=x" in body, "raw markup must not reach the confirmation page")
            assertTrue("&lt;img src=x" in body, "the tool name must still be shown, escaped")
            assertEquals(ApprovalResult.DENIED, result.get(20, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `the listener binds only to loopback`() {
        val announcer = Announcer()
        val requestedBind = AtomicReference<InetSocketAddress>()
        printingServer(
            Duration.ofSeconds(5),
            announcer,
            serverFactory = { address, backlog ->
                requestedBind.set(address)
                HttpServer.create(address, backlog)
            },
        ).use { server ->
            val result = CompletableFuture.supplyAsync { server.requestApproval("ban_user", "chat_id=1") }
            val url = announcer.url()
            assertTrue(url.startsWith("http://127.0.0.1:"), "the link must be loopback-only")
            assertEquals("127.0.0.1", requestedBind.get().address.hostAddress)
            assertTrue(requestedBind.get().address is Inet4Address, "the bind must not silently select IPv6")
            post(url, "deny")
            assertEquals(ApprovalResult.DENIED, result.get(20, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `an oversized post body is rejected without consuming the approval`() {
        val announcer = Announcer()
        printingServer(Duration.ofSeconds(15), announcer).use { server ->
            val result = CompletableFuture.supplyAsync {
                server.requestApproval("ban_user", "chat_id=1, user_id=2")
            }
            val url = announcer.url()

            assertEquals(413, request(url, "POST", "decision=approve&padding=" + "x".repeat(5_000)))
            assertEquals(200, post(url, "deny"), "a rejected body must not decide the request")
            assertEquals(ApprovalResult.DENIED, result.get(20, TimeUnit.SECONDS))
        }
    }

    /**
     * Host log files and agent-visible process output must not carry a working
     * link. Without a watched terminal the server opens the page itself.
     */
    @Test
    fun `without a terminal the page is opened in a browser and the link is not printed`() {
        val announced = CompletableFuture<String>()
        val opened = CompletableFuture<String>()
        LoopbackApprovalServer(
            Duration.ofSeconds(15),
            announce = { line -> announced.complete(line) },
            openBrowser = { url -> opened.complete(url); true },
        ).use { server ->
            val result = CompletableFuture.supplyAsync { server.requestApproval("ban_user", "chat_id=1") }
            val url = opened.get(10, TimeUnit.SECONDS)
            val notice = announced.get(10, TimeUnit.SECONDS)
            assertFalse("nonce=" in notice || "/approve" in notice, "the link must not be printed: $notice")
            assertTrue("ban_user" in notice, "the operator still learns what is waiting: $notice")
            post(url, "approve")
            assertEquals(ApprovalResult.APPROVED, result.get(20, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `no browser and no terminal refuses at once without leaking the link`() {
        val lines = mutableListOf<String>()
        LoopbackApprovalServer(
            Duration.ofSeconds(30),
            announce = { line -> synchronized(lines) { lines += line } },
            openBrowser = { false },
        ).use { server ->
            val startedAt = System.nanoTime()
            assertEquals(ApprovalResult.UNDELIVERABLE, server.requestApproval("ban_user", "chat_id=1"))
            assertTrue((System.nanoTime() - startedAt) / 1_000_000 < 5_000, "must not wait for the timeout")
            assertTrue(lines.none { "nonce=" in it }, "the link must not be printed: $lines")
        }
    }

    /**
     * Knowing the link is not enough to answer: the decision has to come from
     * the rendered page, on its own origin. A forged attempt must not consume
     * the request either, or it could pre-empt the operator's real answer.
     */
    @Test
    fun `a decision from outside the page is refused without consuming the request`() {
        val announcer = Announcer()
        printingServer(Duration.ofSeconds(15), announcer).use { server ->
            val result = CompletableFuture.supplyAsync { server.requestApproval("ban_user", "chat_id=1") }
            val url = announcer.url()
            val token = formToken(url)
            val body = "form_token=$token&decision=approve"

            assertEquals(403, request(url, "POST", body, headers = emptyMap()), "a missing Origin must be refused")
            assertEquals(403, request(url, "POST", body, mapOf("Origin" to "null")))
            assertEquals(403, request(url, "POST", body, mapOf("Origin" to "http://evil.example")))
            assertEquals(
                403,
                request(url, "POST", body, mapOf("Origin" to originOf(url), "Sec-Fetch-Site" to "cross-site")),
            )
            assertEquals(403, request(url, "POST", "decision=approve"), "the page's form token is required")
            assertEquals(403, request(url, "POST", "form_token=wrong&decision=approve"))

            assertEquals(200, post(url, "deny"), "the real answer still decides")
            assertEquals(ApprovalResult.DENIED, result.get(20, TimeUnit.SECONDS))
        }
    }
}
