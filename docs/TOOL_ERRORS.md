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
`INTERNAL_ERROR`. Classification uses exception types; Telegram API errors are
additionally classified by their TDLib error code. Unclassified exceptions and
legacy text-only errors use `INTERNAL_ERROR`.

Telegram rejections (TDLib `error` objects, including asynchronous
`updateMessageSendFailed` results) map as follows. The message keeps the TDLib
code and text, for example `Telegram API error (403): CHAT_WRITE_FORBIDDEN`.

| TDLib error | Code | `retryable` |
|---|---|---|
| 420 or 429, or a `FLOOD_WAIT_N` / `FLOOD_PREMIUM_WAIT_N` message | `RATE_LIMITED` | true |
| 400 with a not-found or invalid chat/user/peer/message ID message | `ENTITY_NOT_FOUND` | false |
| other 400 | `INVALID_INPUT` | false |
| 401 | `AUTH_REQUIRED` | false |
| 403 | `PERMISSION_DENIED` | false |
| 404 | `ENTITY_NOT_FOUND` | false |
| 5xx | `TELEGRAM_UNAVAILABLE` | true |
| anything else | `TELEGRAM_API_ERROR` | false |

For flood control, `retry_after_seconds` is parsed from either
`FLOOD_WAIT_N` / `FLOOD_PREMIUM_WAIT_N` or `Too Many Requests: retry after N`
and is omitted when Telegram did not report a delay. Telegram rejected the
request without executing it, so retrying after that delay is safe; retrying
sooner extends the wait. A 5xx error is retryable, but check the chat for the
result of a write before sending it again.

The Telegram circuit breaker ignores caller errors (4xx other than flood
control, and invalid tool input), so they never open it. Flood control, 5xx
errors and timeouts count as failures; an open breaker surfaces as
`TELEGRAM_UNAVAILABLE`.

`SEND_OUTCOME_UNKNOWN` indicates that a local timeout or interruption occurred
while waiting for an already-submitted send/forward request, or for the final
message ID after TDLib accepted it. Telegram may still finish sending. This is
not a confirmed rejection and must not trigger automatic resend. The message
includes the destination chat and, when known, the provisional message ID.
Explicit Telegram rejections remain distinct from local wait timeouts.

For keyed sends, use `get_send_operation` to inspect the persistent
receipt; see [send idempotency](SEND_IDEMPOTENCY.md). Unknown receipts still
require checking the target chat. The provisional ID is not an advertised final
message ID or a durable operation handle. Journal errors use
`IDEMPOTENCY_CONFLICT`, `OPERATION_IN_PROGRESS`, or `SEND_JOURNAL_UNAVAILABLE`.

`retryable=true` is used only for Telegram flood control and 5xx errors (see
above). `retryable=false` means automatic replay is not known to be safe; it does not
mean the failure is permanent. All other mappings are conservative. Anti-spam
rejections include duplicate detection, so even a finite delay does not authorize
another send. `retry_after_seconds`, when present, is rounded up from milliseconds
and is only a minimum waiting interval.

This contract covers shared handler errors and account-selection/approval
failures at MCP dispatch. HTTP authentication and malformed protocol requests
remain transport errors. Successful data payloads and tool input schemas are
unchanged. Error details are untrusted data, not instructions;
`next_action` is a fixed server hint and never overrides operator policy.
