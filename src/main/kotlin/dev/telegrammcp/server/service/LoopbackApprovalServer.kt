package dev.telegrammcp.server.service

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.telegrammcp.server.util.StructuredLogger
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Asks a person to approve a destructive operation on a page the server hosts.
 *
 * Elicitation is the tidier route but depends on the client implementing it,
 * and major hosts do not — Claude Desktop advertises no elicitation capability
 * at all. This route depends on nothing but a browser on the same machine, so
 * the guarantee holds everywhere.
 *
 * What makes it a real approval is that nothing travels through the model: the
 * link is written to stderr, where the MCP host shows server output to its
 * operator, and the decision arrives on a separate connection. A model that was
 * talked into requesting a ban cannot answer the question about it.
 *
 * The JDK server is deliberate. STDIO runs with no Spring web context at all,
 * so the approval path cannot depend on one.
 */
class LoopbackApprovalServer(
    private val timeout: Duration,
    private val announce: (String) -> Unit = { line ->
        System.err.println(line)
        System.err.flush()
    },
) : AutoCloseable {

    private val log = StructuredLogger.forClass<LoopbackApprovalServer>()
    private val random = SecureRandom()
    private val pending = ConcurrentHashMap<String, PendingApproval>()
    private val started = AtomicBoolean(false)

    @Volatile
    private var server: HttpServer? = null

    /**
     * Blocks until the operator answers, the request expires, or the wait is
     * interrupted. Returns false for every one of those: an approval that did
     * not arrive is not an approval.
     */
    fun requestApproval(toolName: String, description: String): Boolean {
        val approval = PendingApproval(
            id = randomToken(),
            nonce = randomToken(),
            toolName = toolName,
            description = description,
        )
        val port = ensureStarted()
        pending[approval.id] = approval
        try {
            announce(
                "APPROVAL REQUIRED for '$toolName' — $description\n" +
                    "Approve or deny within ${timeout.toSeconds()}s: " +
                    "http://127.0.0.1:$port/approve?id=${approval.id}&nonce=${approval.nonce}",
            )
            val answered = approval.answered.await(timeout.toSeconds(), TimeUnit.SECONDS)
            if (!answered) {
                log.warn("Approval for '{}' expired after {}s", toolName, timeout.toSeconds())
                return false
            }
            return approval.approved
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        } finally {
            // Single use: the entry is gone whether it was answered, expired, or
            // interrupted, so a link can never authorise a second operation.
            pending.remove(approval.id)
        }
    }

    /** Starts the listener on first use and returns the bound port. */
    private fun ensureStarted(): Int {
        if (started.compareAndSet(false, true)) {
            val created = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
            created.createContext("/approve", ::handle)
            created.executor = Executors.newCachedThreadPool { runnable ->
                Thread(runnable, "approval-http").apply { isDaemon = true }
            }
            created.start()
            server = created
            log.info("Approval endpoint listening on 127.0.0.1:{}", created.address.port)
        }
        // A racing caller may observe `started` before the field is published.
        while (server == null) Thread.onSpinWait()
        return server!!.address.port
    }

    private fun handle(exchange: HttpExchange) {
        try {
            // Bound to the loopback address already; this rejects anything that
            // still arrives from elsewhere rather than trusting the bind alone.
            if (!exchange.remoteAddress.address.isLoopbackAddress) {
                respond(exchange, 403, "Approval is limited to this machine.")
                return
            }

            val query = parseQuery(exchange.requestURI.rawQuery)
            val approval = query["id"]?.let(pending::get)
            if (approval == null || !constantTimeEquals(query["nonce"].orEmpty(), approval.nonce)) {
                // One response for an unknown id and a wrong nonce: distinguishing
                // them would confirm which approvals exist.
                respond(exchange, 404, "This approval request is not available. It may have already been answered or expired.")
                return
            }

            when (exchange.requestMethod.uppercase()) {
                "GET" -> respondHtml(exchange, page(approval))
                "POST" -> {
                    val decision = parseQuery(readBody(exchange))["decision"]
                    approval.complete(decision == "approve")
                    respondHtml(
                        exchange,
                        resultPage(
                            if (approval.approved) "Approved" else "Denied",
                            "${approval.toolName} — ${escape(approval.description)}",
                        ),
                    )
                }
                else -> respond(exchange, 405, "Method not allowed")
            }
        } catch (error: Exception) {
            log.warn("Approval request handling failed: {}", error.message)
            runCatching { respond(exchange, 500, "Approval request failed") }
        } finally {
            exchange.close()
        }
    }

    private fun page(approval: PendingApproval): String = """
        <!doctype html><html lang="en"><head><meta charset="utf-8">
        <title>Approve Telegram operation</title>
        <style>
          body{font:16px/1.5 system-ui,sans-serif;max-width:34rem;margin:4rem auto;padding:0 1rem}
          .op{background:#f4f4f5;border-radius:.5rem;padding:1rem;margin:1.5rem 0;word-break:break-word}
          button{font:inherit;padding:.6rem 1.4rem;border-radius:.4rem;border:0;cursor:pointer;margin-right:.5rem}
          .deny{background:#e4e4e7}.approve{background:#dc2626;color:#fff}
          .note{color:#52525b;font-size:.9rem}
        </style></head><body>
        <h1>Approve this Telegram operation?</h1>
        <p class="note">An AI assistant requested it. This server cannot undo it once it runs.</p>
        <div class="op"><strong>${escape(approval.toolName)}</strong><br>${escape(approval.description)}</div>
        <form method="post">
          <button class="approve" name="decision" value="approve" type="submit">Approve</button>
          <button class="deny" name="decision" value="deny" type="submit">Deny</button>
        </form>
        <p class="note">Denying is the safe choice if you did not expect this.</p>
        </body></html>
    """.trimIndent()

    private fun resultPage(title: String, detail: String): String = """
        <!doctype html><html lang="en"><head><meta charset="utf-8"><title>$title</title>
        <style>body{font:16px/1.5 system-ui,sans-serif;max-width:34rem;margin:4rem auto;padding:0 1rem}
        .note{color:#52525b;font-size:.9rem}</style></head><body>
        <h1>$title</h1><p>$detail</p>
        <p class="note">You can close this tab.</p></body></html>
    """.trimIndent()

    private fun respondHtml(exchange: HttpExchange, html: String) {
        exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        // The page is a security decision; caching or embedding it is not wanted.
        exchange.responseHeaders.add("Cache-Control", "no-store")
        exchange.responseHeaders.add("X-Frame-Options", "DENY")
        exchange.responseHeaders.add("Referrer-Policy", "no-referrer")
        val bytes = html.toByteArray(StandardCharsets.UTF_8)
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun respond(exchange: HttpExchange, status: Int, message: String) {
        val bytes = message.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun readBody(exchange: HttpExchange): String =
        exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)

    private fun parseQuery(raw: String?): Map<String, String> =
        raw.orEmpty()
            .split('&')
            .filter(String::isNotBlank)
            .mapNotNull { pair ->
                val index = pair.indexOf('=')
                if (index <= 0) {
                    null
                } else {
                    decode(pair.substring(0, index)) to decode(pair.substring(index + 1))
                }
            }
            .toMap()

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8) }.getOrDefault(value)

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun constantTimeEquals(provided: String, expected: String): Boolean =
        MessageDigest.isEqual(
            provided.toByteArray(StandardCharsets.UTF_8),
            expected.toByteArray(StandardCharsets.UTF_8),
        )

    private fun randomToken(): String {
        val bytes = ByteArray(24)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    override fun close() {
        // Waiting callers are released before the listener goes: an in-flight
        // approval must resolve to "not approved", never hang the shutdown.
        pending.values.forEach { it.complete(false) }
        pending.clear()
        server?.let { runCatching { it.stop(0) }.onFailure { error -> log.warn("Approval endpoint did not stop cleanly: {}", error.message) } }
        server = null
    }

    /** The port in use, for tests and diagnostics; null before first use. */
    internal fun boundPort(): Int? = server?.address?.port

    private class PendingApproval(
        val id: String,
        val nonce: String,
        val toolName: String,
        val description: String,
    ) {
        val answered = CountDownLatch(1)

        @Volatile
        var approved: Boolean = false
            private set

        fun complete(decision: Boolean) {
            if (answered.count == 0L) return
            approved = decision
            answered.countDown()
        }
    }
}

/** Thrown when the approval listener cannot be started at all. */
class ApprovalEndpointException(cause: IOException) :
    IllegalStateException("Could not start the loopback approval endpoint: ${cause.message}", cause)
