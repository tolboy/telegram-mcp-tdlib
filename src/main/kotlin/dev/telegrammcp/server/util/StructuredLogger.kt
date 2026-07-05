package dev.telegrammcp.server.util

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.MDC

/**
 * Thin wrapper around SLF4J that automatically injects MDC keys
 * (`traceId`, `sessionId`, `toolName`) for structured logging.
 *
 * Usage:
 * ```kotlin
 * class MyService {
 *     private val log = StructuredLogger.forClass<MyService>()
 *
 *     fun doWork() {
 *         log.withTool("my_tool").info("Processing request")
 *     }
 * }
 * ```
 */
@Suppress("JAVA_DEFAULT_METHODS_NOT_OVERRIDDEN_BY_DELEGATION")
class StructuredLogger @PublishedApi internal constructor(
    private val delegate: Logger,
) : Logger by delegate {

    companion object {
        const val KEY_TRACE_ID = "traceId"
        const val KEY_SESSION_ID = "sessionId"
        const val KEY_TOOL_NAME = "toolName"

        inline fun <reified T> forClass(): StructuredLogger =
            StructuredLogger(LoggerFactory.getLogger(T::class.java))

        @Suppress("unused")
        fun forName(name: String): StructuredLogger =
            StructuredLogger(LoggerFactory.getLogger(name))
    }

    /**
     * Returns this logger after putting [toolName] into MDC.
     *
     * The key is cleared automatically when the returned logger goes out of
     * scope (caller should avoid storing the reference across threads).
     */
    fun withTool(toolName: String): StructuredLogger {
        MDC.put(KEY_TOOL_NAME, toolName)
        return this
    }

    /**
     * Puts [traceId] and [sessionId] into MDC for the duration of a block.
     */
    @Suppress("unused")
    inline fun <T> withContext(
        traceId: String? = null,
        sessionId: String? = null,
        block: () -> T,
    ): T {
        val prevTrace = MDC.get(KEY_TRACE_ID)
        val prevSession = MDC.get(KEY_SESSION_ID)
        try {
            traceId?.let { MDC.put(KEY_TRACE_ID, it) }
            sessionId?.let { MDC.put(KEY_SESSION_ID, it) }
            return block()
        } finally {
            restoreOrRemove(KEY_TRACE_ID, prevTrace)
            restoreOrRemove(KEY_SESSION_ID, prevSession)
        }
    }

    @PublishedApi
    internal fun restoreOrRemove(key: String, previous: String?) {
        if (previous != null) MDC.put(key, previous) else MDC.remove(key)
    }
}
