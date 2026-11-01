package dev.telegrammcp.server.service

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.telegrammcp.server.util.StructuredLogger
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Asks a person to approve a destructive operation on a page the server hosts.
 *
 * Elicitation is the tidier route but depends on the client implementing it,
 * and major hosts do not — Claude Desktop advertises no elicitation capability
 * at all. This route depends on nothing but a browser on the same machine, so
 * the guarantee holds everywhere.
 *
 * What makes it a real approval is that nothing travels through the model: the
 * link is opened in the operator's browser, and the decision arrives on a
 * separate connection. A model that was talked into requesting a ban cannot
 * answer the question about it.
 *
 * The link is not written to stderr by default. Hosts keep server stderr in log
 * files, and coding agents can often read those or the process output, which
 * would hand a working approval link to the very party it guards against. It
 * is printed only when [printLink] is set (stderr is a terminal a person
 * watches, or `MCP_APPROVAL_LINK_ON_STDERR=true`). Otherwise the server opens
 * the page itself and, failing that, refuses rather than leaking the link.
 *
 * The decision POST additionally needs the page's own Origin and a per-request
 * form token that only the rendered page carries, so following the link from
 * a tool that can merely fetch URLs is not enough to approve.
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
    private val serverFactory: (InetSocketAddress, Int) -> HttpServer = { address, backlog ->
        HttpServer.create(address, backlog)
    },
    /** Print the full link through [announce] instead of opening a browser. */
    private val printLink: Boolean = false,
    /** Opens the link in the operator's browser; false when no browser could be started. */
    private val openBrowser: (String) -> Boolean = ::openInBrowser,
) : AutoCloseable {

    init {
        require(!timeout.isZero && !timeout.isNegative) {
            "Destructive approval timeout must be positive"
        }
    }

    private val log = StructuredLogger.forClass<LoopbackApprovalServer>()
    private val random = SecureRandom()
    private val pending = ConcurrentHashMap<String, PendingApproval>()
    private val lifecycleLock = Any()

    @Volatile
    private var server: HttpServer? = null

    @Volatile
    private var executor: ExecutorService? = null

    @Volatile
    private var closed = false

    /**
     * Blocks until the operator answers, the request expires, or the wait is
     * interrupted. Every non-approval has a distinct fail-closed result so the
     * caller can report a denial separately from silence or shutdown.
     */
    internal fun requestApproval(toolName: String, description: String): ApprovalResult {
        val approval = PendingApproval(
            id = randomToken(),
            nonce = randomToken(),
            formToken = randomToken(),
            toolName = toolName,
            description = description,
        )
        val port = startAndRegister(approval) ?: return ApprovalResult.UNAVAILABLE
        try {
            val url = "http://127.0.0.1:$port/approve?id=${approval.id}&nonce=${approval.nonce}"
            if (!deliver(toolName, description, url)) return ApprovalResult.UNDELIVERABLE
            val answered = approval.answered.await(timeout.toMillis(), TimeUnit.MILLISECONDS)
            if (!answered) {
                log.warn("Approval for '{}' expired after {}s", toolName, timeout.toSeconds())
                return ApprovalResult.TIMED_OUT
            }
            return approval.result()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            return ApprovalResult.TIMED_OUT
        } finally {
            // Single use: the entry is gone whether it was answered, expired, or
            // interrupted, so a link can never authorise a second operation.
            pending.remove(approval.id)
        }
    }

    /**
     * Gets the link to a person without putting it where an agent reads.
     * Returns false when no channel is available; the caller then refuses.
     */
    private fun deliver(toolName: String, description: String, url: String): Boolean {
        val header = "APPROVAL REQUIRED for '$toolName' — $description"
        if (printLink) {
            announce("$header\nApprove or deny within ${timeout.toSeconds()}s: $url")
            return true
        }
        val opened = runCatching { openBrowser(url) }
            .onFailure { error -> log.warn("Could not open the approval page: {}", error.message) }
            .getOrDefault(false)
        if (opened) {
            announce(
                "$header\nThe approval page was opened in your browser; answer there within " +
                    "${timeout.toSeconds()}s. The link is not printed so that an agent reading " +
                    "this output cannot follow it (MCP_APPROVAL_LINK_ON_STDERR=true prints it).",
            )
            return true
        }
        announce(
            "$header\nRefused: no browser could be opened for the approval page, and the link is " +
                "not printed where an agent could read it. Set MCP_APPROVAL_LINK_ON_STDERR=true if " +
                "only you can see this output, or use MCP_DESTRUCTIVE_APPROVAL=elicitation.",
        )
        return false
    }

    /**
     * Starts the listener on first use and registers the request atomically with
     * respect to [close]. A caller can therefore neither be registered after
     * shutdown's snapshot nor wait on an endpoint that shutdown already missed.
     */
    private fun startAndRegister(approval: PendingApproval): Int? = synchronized(lifecycleLock) {
        if (closed) return@synchronized null

        var active = server
        if (active == null) {
            var created: HttpServer? = null
            var createdExecutor: ExecutorService? = null
            try {
                // Do not use getLoopbackAddress(): it is allowed to return ::1,
                // while the announced URL and security contract are explicitly IPv4.
                created = serverFactory(InetSocketAddress(IPV4_LOOPBACK, 0), 0)
                created.createContext("/approve", ::handle)
                createdExecutor = Executors.newCachedThreadPool { runnable ->
                    Thread(runnable, "approval-http").apply { isDaemon = true }
                }
                created.executor = createdExecutor
                created.start()
            } catch (error: Exception) {
                created?.let { runCatching { it.stop(0) } }
                createdExecutor?.shutdownNow()
                throw ApprovalEndpointException(error)
            }

            server = created
            executor = createdExecutor
            active = created
            log.info("Approval endpoint listening on 127.0.0.1:{}", created.address.port)
        }

        pending[approval.id] = approval
        active.address.port
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
                    if (!sameOriginPost(exchange)) {
                        // Not consumed: a forged cross-site POST must not be
                        // able to deny the operator's real decision either.
                        respond(exchange, 403, "The decision must be submitted from the approval page.")
                        return
                    }
                    val body = readBody(exchange) ?: return
                    val form = parseQuery(body)
                    if (!constantTimeEquals(form["form_token"].orEmpty(), approval.formToken)) {
                        respond(exchange, 403, "The decision must be submitted from the approval page.")
                        return
                    }
                    val decision = form["decision"]
                    approval.complete(
                        if (decision == "approve") ApprovalResult.APPROVED else ApprovalResult.DENIED,
                    )
                    pending.remove(approval.id, approval)
                    respondHtml(
                        exchange,
                        resultPage(
                            if (approval.result() == ApprovalResult.APPROVED) "Approved" else "Denied",
                            "${escape(approval.toolName)} — ${escape(approval.description)}",
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

    /**
     * Browsers send `Origin` on every form POST (the page's Referrer-Policy is
     * `same-origin` so it is not blanked to `null`). Requiring the exact
     * loopback origin keeps other sites and bare URL fetchers from answering.
     */
    private fun sameOriginPost(exchange: HttpExchange): Boolean {
        val expected = "http://127.0.0.1:${exchange.localAddress.port}"
        val origin = exchange.requestHeaders.getFirst("Origin") ?: return false
        if (origin != expected) return false
        val fetchSite = exchange.requestHeaders.getFirst("Sec-Fetch-Site")
        return fetchSite == null || fetchSite == "same-origin"
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
          <input type="hidden" name="form_token" value="${escape(approval.formToken)}">
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
        // same-origin, not no-referrer: with no-referrer browsers send
        // `Origin: null` on the form POST and the origin check cannot pass.
        exchange.responseHeaders.add("Referrer-Policy", "same-origin")
        exchange.responseHeaders.add(
            "Content-Security-Policy",
            "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'",
        )
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

    private fun readBody(exchange: HttpExchange): String? {
        val declaredSize = exchange.requestHeaders.getFirst("Content-Length")?.toLongOrNull()
        if (declaredSize != null && declaredSize > MAX_POST_BODY_BYTES) {
            respond(exchange, 413, "Approval response is too large")
            return null
        }
        val bytes = exchange.requestBody.readNBytes(MAX_POST_BODY_BYTES + 1)
        if (bytes.size > MAX_POST_BODY_BYTES) {
            respond(exchange, 413, "Approval response is too large")
            return null
        }
        return bytes.toString(StandardCharsets.UTF_8)
    }

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
        val toClose = synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            val snapshot = CloseSnapshot(server, executor, pending.values.toList())
            server = null
            executor = null
            pending.clear()
            snapshot
        }

        // Release waiters outside the lifecycle monitor before stopping the
        // listener. PendingApproval is atomic, so a simultaneous POST and close
        // have one stable winner and can never overwrite each other's decision.
        toClose.approvals.forEach { it.complete(ApprovalResult.UNAVAILABLE) }
        toClose.server?.let {
            runCatching { it.stop(0) }
                .onFailure { error -> log.warn("Approval endpoint did not stop cleanly: {}", error.message) }
        }
        toClose.executor?.shutdownNow()
    }

    /** The port in use, for tests and diagnostics; null before first use. */
    internal fun boundPort(): Int? = server?.address?.port

    private class PendingApproval(
        val id: String,
        val nonce: String,
        val formToken: String,
        val toolName: String,
        val description: String,
    ) {
        val answered = CountDownLatch(1)
        private val decision = AtomicReference<ApprovalResult?>(null)

        fun complete(decision: ApprovalResult) {
            if (this.decision.compareAndSet(null, decision)) {
                answered.countDown()
            }
        }

        fun result(): ApprovalResult = decision.get() ?: ApprovalResult.UNAVAILABLE
    }

    private data class CloseSnapshot(
        val server: HttpServer?,
        val executor: ExecutorService?,
        val approvals: List<PendingApproval>,
    )

    private companion object {
        private const val MAX_POST_BODY_BYTES = 4_096
        private val IPV4_LOOPBACK: InetAddress =
            InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    }

    internal enum class ApprovalResult {
        APPROVED,
        DENIED,
        TIMED_OUT,
        UNAVAILABLE,

        /** No channel to the operator that an agent cannot also read. */
        UNDELIVERABLE,
    }
}

/**
 * Opens [url] with the platform's default browser. The child gets no stdin and
 * its output is discarded, so it cannot touch an STDIO transport.
 */
internal fun openInBrowser(url: String): Boolean {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    val command = when {
        os.contains("win") -> listOf("rundll32", "url.dll,FileProtocolHandler", url)
        os.contains("mac") -> listOf("open", url)
        System.getenv("DISPLAY").isNullOrBlank() && System.getenv("WAYLAND_DISPLAY").isNullOrBlank() -> return false
        else -> listOf("xdg-open", url)
    }
    return try {
        val process = ProcessBuilder(command)
            .redirectInput(ProcessBuilder.Redirect.from(java.io.File(if (os.contains("win")) "NUL" else "/dev/null")))
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        // A launcher that is still running after a few seconds has handed off.
        !process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() == 0
    } catch (error: java.io.IOException) {
        false
    } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }
}

/** True when stderr is an interactive terminal a person is presumably watching. */
internal fun stderrIsTerminal(): Boolean = runCatching { System.console()?.isTerminal == true }.getOrDefault(false)

/** Thrown when the approval listener cannot be started at all. */
class ApprovalEndpointException(cause: Exception) :
    IllegalStateException("Could not start the loopback approval endpoint: ${cause.message}", cause)
