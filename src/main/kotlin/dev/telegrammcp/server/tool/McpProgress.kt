package dev.telegrammcp.server.tool

import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import org.slf4j.LoggerFactory

/**
 * Reports start and finish when the caller supplied `_meta.progressToken`.
 *
 * Streamable HTTP `notifications/cancelled` is accepted by
 * [dev.telegrammcp.server.security.McpCancellationFilter] and only stops the
 * local wait. Closing the HTTP response is not cancellation. MCP Java SDK
 * 2.1.0 still reads STDIO on the tool thread, so a STDIO cancel cannot be
 * observed until that call returns.
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
