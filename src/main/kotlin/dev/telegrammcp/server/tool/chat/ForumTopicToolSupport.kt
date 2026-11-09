package dev.telegrammcp.server.tool.chat

import dev.telegrammcp.server.exception.InvalidToolInputException
import dev.telegrammcp.server.tool.ToolNumbers

internal fun parseForumTopicThreadId(arguments: Map<String, Any>): Long {
    val field = listOf("message_thread_id", "topic_id", "thread_id").firstOrNull(arguments::containsKey)
        ?: throw InvalidToolInputException("message_thread_id is required")
    return ToolNumbers.long(arguments[field], field)
}

internal fun parseBoolean(raw: Any?, default: Boolean): Boolean =
    raw?.toString()?.toBooleanStrictOrNull() ?: default
