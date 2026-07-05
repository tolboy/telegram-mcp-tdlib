package dev.telegrammcp.server

import dev.telegrammcp.server.cli.SessionMaintenanceCli
import dev.telegrammcp.server.cli.TelegramMcpCli
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
    runApplication<TelegramMcpApplication>(*args)
}
