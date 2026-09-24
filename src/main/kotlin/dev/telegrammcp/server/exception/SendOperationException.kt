package dev.telegrammcp.server.exception

class SendOperationConflictException : McpException("Idempotency key is already bound to a different send request")
class SendOperationBusyException : McpException("Send operation is in progress; check its status instead of sending again")
class SendJournalException(cause: Throwable? = null) :
    McpException("Send journal is unavailable or damaged; no automatic resend is permitted", cause)
