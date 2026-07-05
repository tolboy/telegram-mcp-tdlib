package dev.telegrammcp.server.tool

import dev.telegrammcp.server.exception.InvalidToolInputException
import dev.telegrammcp.server.model.ParseMode

/**
 * Shared argument parsing helpers for MCP tools.
 */
object ToolInputParsers {

    fun parseMode(args: Map<String, Any>, key: String = "parse_mode"): ParseMode {
        val raw = args[key]?.toString()?.lowercase() ?: return ParseMode.PLAIN
        return when (raw) {
            "plain" -> ParseMode.PLAIN
            "html" -> ParseMode.HTML
            "markdown", "md" -> ParseMode.MARKDOWN
            else -> throw InvalidToolInputException("parse_mode must be one of: plain, html, markdown")
        }
    }
}

