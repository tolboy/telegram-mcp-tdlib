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
 * deadline, then a halt — so a signal only needs to reach it. The hook remains
 * alive until that halt path is reached because the shutdown workers are
 * daemon threads and the JVM does not otherwise wait for them.
 *
 * Registration happens before Spring startup so a signal received during a
 * blocked initializer is still bounded. Callers remove the hook if startup
 * fails normally, preserving the original startup exit code.
 *
 * Returns the registered hook, or null when the JVM was already shutting down.
 * A shutdown hook is not a live thread until the JVM starts it, so it cannot be
 * looked up by name; callers that need to deregister it use the returned value.
 */
fun installSignalShutdownHook(shutdown: ServerShutdown): Thread? =
    installSignalShutdownHook(shutdown, Runtime.getRuntime()::addShutdownHook)

/**
 * Removes a pre-start hook after an ordinary startup failure. A concurrent
 * signal may have started JVM shutdown already, in which case removal is no
 * longer legal and the running hook must finish the bounded shutdown.
 */
fun removeSignalShutdownHook(hook: Thread?): Boolean {
    if (hook == null) return false
    return try {
        Runtime.getRuntime().removeShutdownHook(hook)
    } catch (_: IllegalStateException) {
        false
    }
}

/**
 * Seam for the registration itself, which is the part that behaves differently
 * once the JVM has started shutting down and cannot be provoked from a test.
 */
internal fun installSignalShutdownHook(
    shutdown: ServerShutdown,
    register: (Thread) -> Unit,
): Thread? {
    val hook = Thread(
        { shutdown.requestShutdownAndAwait("received a JVM termination signal") },
        "signal-shutdown",
    )
    return try {
        register(hook)
        hook
    } catch (alreadyShuttingDown: IllegalStateException) {
        shutdown.requestShutdownAndAwait("termination signal received while the server was still starting")
        null
    }
}
