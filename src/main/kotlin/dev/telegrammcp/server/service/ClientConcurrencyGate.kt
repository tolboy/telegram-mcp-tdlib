package dev.telegrammcp.server.service

import dev.telegrammcp.server.exception.TelegramBusyException
import dev.telegrammcp.server.security.AccessPermissionService
import java.util.concurrent.ConcurrentHashMap

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

    private val inFlight = ConcurrentHashMap<String, Int>()

    fun <T> run(block: () -> T): T {
        val client = AccessPermissionService.clientIdentity().ifBlank { "anonymous" }
        inFlight.compute(client) { _, count ->
            if ((count ?: 0) >= limit) throw TelegramBusyException("client:$client", limit, retryable = true)
            (count ?: 0) + 1
        }
        return try {
            block()
        } finally {
            // Removal and acquisition must share compute's per-key lock; removing
            // a zero AtomicInteger separately could create two counters for a client.
            inFlight.computeIfPresent(client) { _, count -> if (count == 1) null else count - 1 }
        }
    }
}
