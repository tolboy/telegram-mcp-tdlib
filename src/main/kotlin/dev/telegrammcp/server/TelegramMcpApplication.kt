package dev.telegrammcp.server

import dev.telegrammcp.server.cli.SessionMaintenanceCli
import dev.telegrammcp.server.cli.TelegramMcpCli
import dev.telegrammcp.server.runtime.ServerShutdown
import dev.telegrammcp.server.runtime.installSignalShutdownHook
import dev.telegrammcp.server.runtime.removeSignalShutdownHook
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

/**
 * Entry point for the Telegram MCP Server.
 *
 * Exposes Telegram capabilities as MCP tools over Streamable HTTP, secured by
 * API-key authentication with full observability.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
class TelegramMcpApplication

fun main(args: Array<String>) {
    if (SessionMaintenanceCli.tryRun(args)) return
    if (TelegramMcpCli.run(args)) return
    // Install before the blocking Spring startup so SIGTERM is bounded even
    // while an initializer is stuck. On an ordinary startup failure the hook is
    // removed and the original exception keeps its non-zero process exit code.
    val signalHook = installSignalShutdownHook(ServerShutdown.INSTANCE)
    try {
        ServerShutdown.INSTANCE.attach(runApplication<TelegramMcpApplication>(*args))
    } catch (startupFailure: Throwable) {
        removeSignalShutdownHook(signalHook)
        throw startupFailure
    }
}
