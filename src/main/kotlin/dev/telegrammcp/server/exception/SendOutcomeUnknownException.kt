package dev.telegrammcp.server.exception

/**
 * A local wait ended after submission; Telegram may still complete the send.
 * [deadlineExceeded] is true when the tool call's overall deadline, rather
 * than the full per-request timeout, ended the wait; such a timeout says
 * nothing about Telegram's health and does not count toward the breaker.
 */
class SendOutcomeUnknownException(
    val chatId: Long,
    val provisionalMessageId: Long? = null,
    cause: Throwable,
    val deadlineExceeded: Boolean = false,
) : McpException(
    "Telegram send outcome is unknown in chat $chatId" +
        (provisionalMessageId?.let { " (provisional message $it)" } ?: "") +
        (if (deadlineExceeded) " because the tool call's deadline ran out" else "") +
        ". The message may still be sent. Check the chat before sending again; do not automatically retry.",
    cause,
)
