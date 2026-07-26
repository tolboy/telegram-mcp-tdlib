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
 * Returns the registered hook so a caller can deregister it; a shutdown hook is
 * not a live thread until the JVM starts it, so it cannot be looked up by name.
 */
fun installSignalShutdownHook(shutdown: ServerShutdown): Thread {
    val hook = Thread(
        { shutdown.requestShutdown("received a JVM termination signal") },
        "signal-shutdown",
    )
    Runtime.getRuntime().addShutdownHook(hook)
    return hook
}
