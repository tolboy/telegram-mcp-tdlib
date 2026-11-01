package dev.telegrammcp.server.tool

import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import org.slf4j.LoggerFactory

/**
 * Reports start and finish when the caller supplied `_meta.progressToken`.
 *
 * The MCP Java SDK 2.1.0 does not expose `notifications/cancelled` to a tool
 * handler, so a client cancel is not observable here. Closing the HTTP
 * response is also not treated as cancellation on the 2025-06-18 lifecycle.
 */
object McpProgress {
    private val log = LoggerFactory.getLogger(McpProgress::class.java)

    fun token(meta: Map<*, *>?): Any? {
        val value = meta?.get("progressToken") ?: return null
        return when (value) {
            is String -> value
            is Number -> value.takeIf { it.toDouble().isFinite() }
            else -> null
        }
    }

    fun report(exchange: McpSyncServerExchange, token: Any?, progress: Double, message: String) {
        if (token == null) return
        val sent = runCatching {
            val notification = McpSchema.ProgressNotification.builder(token, progress)
                .total(1.0)
                .message(message)
                .build()
            exchange.progressNotification(notification)
        }
        sent.exceptionOrNull()?.let { error ->
            log.debug("Progress notification was not delivered: {}", error.message)
        }
    }
}
