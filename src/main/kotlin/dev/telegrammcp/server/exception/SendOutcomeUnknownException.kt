package dev.telegrammcp.server.exception

/** A local wait ended after submission; Telegram may still complete the send. */
class SendOutcomeUnknownException(
    val chatId: Long,
    val provisionalMessageId: Long? = null,
    cause: Throwable,
) : McpException(
    "Telegram send outcome is unknown in chat $chatId" +
        (provisionalMessageId?.let { " (provisional message $it)" } ?: "") +
        ". The message may still be sent. Check the chat before sending again; do not automatically retry.",
    cause,
)
