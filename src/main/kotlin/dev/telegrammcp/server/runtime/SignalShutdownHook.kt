package dev.telegrammcp.server.runtime

/**
 * Routes JVM termination signals into [ServerShutdown].
 *
 * Spring registers its own shutdown hook, but that hook has no deadline. The
 * context close runs TDLib's `@PreDestroy`, and closing a TDLib factory can
 * block, so the JVM waits on the hook for as long as it takes. A supervisor
 * that expects the process to end gives up and escalates: `docker stop`,
 * `compose down`, systemd and Kubernetes all send SIGTERM and then SIGKILL,
 * which kills TDLib mid-write instead of letting it flush.
 *
 * [ServerShutdown] already bounds exactly this — a graceful close under a hard
 * deadline, then a halt — so a signal only needs to reach it. Both hooks run
 * concurrently and both close the same context; that close is idempotent, and
 * whichever finishes first releases the other.
 *
 * Registration deliberately happens after startup succeeds, so a failed startup
 * keeps its own exit code instead of this hook's clean one. That leaves a window
 * in which the signal arrives first — startup includes loading TDLib's native
 * libraries and is not instant — and the JVM then refuses a new hook because it
 * is already shutting down. That case still needs the deadline, so it requests
 * the shutdown directly rather than letting Spring's unbounded hook run alone.
 *
 * Returns the registered hook, or null when the JVM was already shutting down.
 * A shutdown hook is not a live thread until the JVM starts it, so it cannot be
 * looked up by name; callers that need to deregister it use the returned value.
 */
fun installSignalShutdownHook(shutdown: ServerShutdown): Thread? =
    installSignalShutdownHook(shutdown, Runtime.getRuntime()::addShutdownHook)

/**
 * Seam for the registration itself, which is the part that behaves differently
 * once the JVM has started shutting down and cannot be provoked from a test.
 */
internal fun installSignalShutdownHook(
    shutdown: ServerShutdown,
    register: (Thread) -> Unit,
): Thread? {
    val hook = Thread(
        { shutdown.requestShutdown("received a JVM termination signal") },
        "signal-shutdown",
    )
    return try {
        register(hook)
        hook
    } catch (alreadyShuttingDown: IllegalStateException) {
        shutdown.requestShutdown("termination signal received while the server was still starting")
        null
    }
}
