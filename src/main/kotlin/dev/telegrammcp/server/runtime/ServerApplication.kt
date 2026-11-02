package dev.telegrammcp.server.runtime

import org.springframework.boot.builder.SpringApplicationBuilder

/** Spring Boot property that makes the logging system register its own JVM shutdown hook. */
internal const val LOGGING_SHUTDOWN_HOOK_PROPERTY = "logging.register-shutdown-hook"

/**
 * Builds the Spring application for a long-running server process, with
 * [ServerShutdown] as the only owner of process termination.
 *
 * Spring Boot registers its JVM shutdown hook lazily, part-way through startup:
 * the logging listener does it while the environment is prepared, and
 * `SpringApplication` does it again when the context refreshes. A termination
 * signal that arrives before either point has already started JVM shutdown, so
 * the late `Runtime.addShutdownHook` throws `IllegalStateException: Shutdown in
 * progress`. Spring then logs "Application run failed" with a stack trace and
 * the main thread dies, even though the signal hook goes on to end the process
 * cleanly. A supervisor that stops a starting server (`docker stop`, systemd,
 * an MCP client giving up) sees a startup failure that did not happen.
 *
 * The signal hook from [installSignalShutdownHook] is registered before Spring
 * starts and already closes the context under a deadline, so Spring's hook adds
 * only this race. `registerShutdownHook(false)` stops Spring from adding its JVM
 * hook at all, for the context and for the logging system alike. The logging
 * property is set as well so the logging system does not queue a shutdown
 * handler that would never run; it needs none, because every appender is a
 * synchronous console appender with nothing to flush on exit.
 */
fun serverApplicationBuilder(vararg sources: Class<*>): SpringApplicationBuilder =
    SpringApplicationBuilder(*sources)
        .registerShutdownHook(false)
        .properties("$LOGGING_SHUTDOWN_HOOK_PROPERTY=false")
