package dev.telegrammcp.server.service

import dev.telegrammcp.server.exception.TelegramBusyException
import dev.telegrammcp.server.security.AccessPermissionService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Caps how many tool calls one MCP client may hold at once. The TDLib bulkhead
 * is per account and is shared by every client of the daemon; this gate stops
 * one principal from taking all of those slots.
 */
class ClientConcurrencyGate(
    private val limit: Int,
) {
    init {
        require(limit > 0) { "mcp.max-concurrent-calls-per-client must be positive" }
    }

    private val inFlight = ConcurrentHashMap<String, AtomicInteger>()

    fun <T> run(block: () -> T): T {
        val client = AccessPermissionService.clientIdentity().ifBlank { "anonymous" }
        val counter = inFlight.computeIfAbsent(client) { AtomicInteger() }
        if (counter.incrementAndGet() > limit) {
            counter.decrementAndGet()
            throw TelegramBusyException("client:$client", limit, retryable = true)
        }
        return try {
            block()
        } finally {
            counter.decrementAndGet()
        }
    }
}
