package dev.telegrammcp.server.tool

/** Explicit wire contracts; no inference from Kotlin signatures or new input keywords. */
object ToolOutputSchemas {
    private val text = mapOf<String, Any>("type" to "string")
    private val boolean = mapOf<String, Any>("type" to "boolean")
    private val integer = mapOf<String, Any>("type" to "integer")
    private val count = integer + ("minimum" to 0)
    private val nullableText = mapOf<String, Any>("type" to listOf("string", "null"))
    private val nullableId = mapOf<String, Any>("type" to listOf("integer", "null"))
    private fun array(items: Map<String, Any>) = mapOf<String, Any>("type" to "array", "items" to items)
    private fun values(vararg choices: String) = text + ("enum" to choices.toList())
    private fun obj(properties: Map<String, Any>, required: List<String> = properties.keys.toList()) = mapOf<String, Any>(
        "type" to "object", "properties" to properties, "required" to required, "additionalProperties" to false,
    )

    val error = obj(mapOf("error" to obj(mapOf(
        "code" to (text + ("minLength" to 1)), "message" to text, "retryable" to boolean,
        "retry_after_seconds" to (nullableId + ("minimum" to 0)), "next_action" to text,
    ))))

    private val pollOption = obj(mapOf(
        "text" to text, "voterCount" to count, "votePercentage" to count, "isChosen" to boolean,
    ))
    private val poll = obj(mapOf(
        "question" to text, "totalVoterCount" to count, "isAnonymous" to boolean,
        "isClosed" to boolean, "options" to array(pollOption),
    )) + ("type" to listOf("object", "null"))

    val message = obj(mapOf(
        "messageId" to integer, "chatId" to integer, "chatTitle" to nullableText,
        "senderName" to text, "senderId" to nullableId, "text" to nullableText,
        // Existing Jackson settings may emit either epoch seconds or ISO text.
        "date" to mapOf("type" to listOf("string", "number")),
        "replyToMessageId" to nullableId, "messageThreadId" to nullableId,
        "mediaType" to nullableText, "forwardFrom" to nullableText, "poll" to poll,
    ), listOf("messageId", "chatId", "chatTitle", "senderName", "text", "date"))

    private fun receiptVariant(status: Map<String, Any>, messageId: Map<String, Any>) = obj(mapOf(
        "operation_id" to (text + ("pattern" to "^[0-9a-f]{64}$")),
        "chat_id" to integer, "status" to status, "message_id" to messageId, "replayed" to boolean,
        "message_ids" to (array(integer + ("minimum" to 1)) + ("minItems" to 1)),
    ), listOf("operation_id", "chat_id", "status", "message_id"))

    val receipt = mapOf<String, Any>("anyOf" to listOf(
        receiptVariant(values("SENT", "SCHEDULED"), integer + ("minimum" to 1)),
        receiptVariant(values("UNKNOWN", "NOT_FOUND"), mapOf("type" to "null")),
    ))

    val sendResult = mapOf<String, Any>("anyOf" to listOf(message, receipt))

    val export = obj(mapOf(
        "chat_id" to integer, "since" to nullableText, "until" to nullableText,
        "limit" to (integer + mapOf("minimum" to 1, "maximum" to 500)),
        "query_terms" to array(text), "history_messages" to count, "search_messages" to count,
        "total" to count, "truncated" to boolean, "complete" to boolean,
        "completion_scope" to values("requested_sources"),
        "partial_reasons" to array(values("result_limit", "page_limit", "cursor_stalled")),
        "scanned_count" to count, "messages" to array(message),
    ))

    private val searchChat = obj(mapOf(
        "chat" to text, "chat_id" to integer, "messages" to array(message), "complete" to boolean,
        "partial_reasons" to array(values("query_failed", "result_limit", "resolution_failed", "chat_failed")),
        "failed_queries" to array(obj(mapOf("query" to text, "error" to text))),
        "truncated" to boolean, "scanned_count" to count, "error" to text,
    ), listOf("chat", "messages", "complete", "partial_reasons"))

    val search = obj(mapOf(
        "query" to text, "search_queries" to array(text),
        "limit_per_chat" to (integer + ("minimum" to 1)), "chats" to array(searchChat),
        "complete" to boolean, "completion_scope" to values("requested_query_pages"),
        "incomplete_chats" to array(obj(mapOf("index" to count, "chat" to text, "reason" to values("timeout")))),
        "timed_out" to boolean, "timeout_ms" to count,
    ), listOf("query", "search_queries", "limit_per_chat", "chats", "complete", "completion_scope", "incomplete_chats"))
}
