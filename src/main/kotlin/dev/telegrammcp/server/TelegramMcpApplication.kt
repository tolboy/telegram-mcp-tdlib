package dev.telegrammcp.server

import dev.telegrammcp.server.cli.SessionMaintenanceCli
import dev.telegrammcp.server.cli.TelegramMcpCli
import dev.telegrammcp.server.runtime.ServerShutdown
import dev.telegrammcp.server.runtime.installSignalShutdownHook
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
    // The legacy no-command startup gets the same exit path as `serve`, so an
    // unrecoverable TDLib failure or a termination signal can still close the
    // context before halting. The signal hook waits until startup succeeded:
    // registering it earlier would halt a failed startup with its own clean
    // exit code and report success for a server that never started.
    ServerShutdown.INSTANCE.attach(runApplication<TelegramMcpApplication>(*args))
    installSignalShutdownHook(ServerShutdown.INSTANCE)
}
