package dev.telegrammcp.server.tool.chat

import dev.telegrammcp.server.exception.InvalidToolInputException

internal fun parseForumTopicThreadId(arguments: Map<String, Any>): Long {
    val raw = arguments["message_thread_id"]
        ?: arguments["topic_id"]
        ?: arguments["thread_id"]
        ?: throw InvalidToolInputException("message_thread_id is required")
    return parseRequiredLong(raw, "message_thread_id")
}

internal fun parseOptionalLong(raw: Any?, field: String): Long? {
    if (raw == null) return null
    return parseRequiredLong(raw, field)
}

internal fun parseBoolean(raw: Any?, default: Boolean): Boolean =
    raw?.toString()?.toBooleanStrictOrNull() ?: default

private fun parseRequiredLong(raw: Any, field: String): Long =
    when (raw) {
        is Number -> raw.toLong()
        is String -> raw.trim().takeIf { it.isNotBlank() }?.toLongOrNull()
            ?: throw InvalidToolInputException("$field must be an integer")
        else -> throw InvalidToolInputException("$field must be an integer")
    }
