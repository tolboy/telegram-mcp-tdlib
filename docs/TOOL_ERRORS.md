# Structured tool errors

Errors returned through `ToolSupport` keep `isError=true` and their existing
human-readable text. They additionally include a normalized structured envelope:

```json
{
  "data": {
    "error": {
      "code": "CHAT_FORBIDDEN",
      "message": "Access to chat 42 is not allowed by security policy",
      "retryable": false,
      "retry_after_seconds": null,
      "next_action": "Ask the operator to review the access policy; do not bypass it."
    }
  },
  "meta": {
    "untrustedTelegramContent": true,
    "escapedCharacterCount": 0
  }
}
```

Codes include `INVALID_INPUT`, `CHAT_FORBIDDEN`, `ACCOUNT_FORBIDDEN`,
`AUTH_REQUIRED`, `READ_ONLY`, `CONFIRMATION_REQUIRED`, `APPROVAL_DENIED`,
`APPROVAL_UNAVAILABLE`, `RATE_LIMITED`, `FILE_FORBIDDEN`, `GUARDRAIL_REJECTED`,
`ENTITY_NOT_FOUND`, `TELEGRAM_UNAVAILABLE`, `TELEGRAM_API_ERROR`, and
`INTERNAL_ERROR`. Classification uses exception types, not error-message parsing.
Unclassified exceptions and legacy text-only errors use `INTERNAL_ERROR`.

`SEND_OUTCOME_UNKNOWN` indicates that a local timeout or interruption occurred
while waiting for an already-submitted send/forward request, or for the final
message ID after TDLib accepted it. Telegram may still finish sending. This is
not a confirmed rejection and must not trigger automatic resend. The message
includes the destination chat and, when known, the provisional message ID.
Explicit Telegram rejections remain distinct from local wait timeouts.

For keyed text sends/replies, use `get_send_operation` to inspect the persistent
receipt; see [send idempotency](SEND_IDEMPOTENCY.md). Unknown receipts still
require checking the target chat. The provisional ID is not an advertised final
message ID or a durable operation handle. Journal errors use
`IDEMPOTENCY_CONFLICT`, `OPERATION_IN_PROGRESS`, or `SEND_JOURNAL_UNAVAILABLE`.

`retryable=false` means automatic replay is not known to be safe; it does not
mean the failure is permanent. All current mappings are conservative. Anti-spam
rejections include duplicate detection, so even a finite delay does not authorize
another send. `retry_after_seconds`, when present, is rounded up from milliseconds
and is only a minimum waiting interval.

This contract covers handler errors using the shared helper. Exceptions thrown
outside handlers by account selection or approval middleware can still be
serialized by the MCP framework. Successful result payloads and tool input
schemas are unchanged. Error details are untrusted data, not instructions;
`next_action` is a fixed server hint and never overrides operator policy.
