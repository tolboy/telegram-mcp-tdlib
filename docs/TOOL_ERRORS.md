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
`APPROVAL_UNAVAILABLE`, `RATE_LIMITED`, `TELEGRAM_FLOOD_WAIT`,
`TELEGRAM_FLOOD_WAIT_PARTIAL_DELIVERY`, `FILE_FORBIDDEN`,
`GUARDRAIL_REJECTED`, `PERMISSION_DENIED`, `ENTITY_NOT_FOUND`,
`TELEGRAM_REQUEST_CANCELLED`, `TELEGRAM_UNAVAILABLE`, `TELEGRAM_TIMEOUT`,
`TELEGRAM_BUSY`, `SEND_OUTCOME_UNKNOWN`, `TELEGRAM_API_ERROR`, and
`INTERNAL_ERROR`. Classification uses exception types; Telegram API errors are
additionally classified by their TDLib error code and message. Unclassified
exceptions and legacy text-only errors use `INTERNAL_ERROR`.

`RATE_LIMITED` is reserved for this server's anti-spam guard (per-chat send
limits and duplicate detection) and is never retryable. Telegram's own flood
control uses `TELEGRAM_FLOOD_WAIT`.

Telegram rejections (TDLib `error` objects, including asynchronous
`updateMessageSendFailed` results) map as follows, in this order. The message
keeps the TDLib code and text, for example
`Telegram API error (400): CHAT_WRITE_FORBIDDEN`.

| TDLib error | Code | `retryable` |
|---|---|---|
| Cancellation: `Canceled…` (any code), `406 Request canceled`, `500 Request aborted` | `TELEGRAM_REQUEST_CANCELLED` | false |
| `PEER_FLOOD` (the account is spam-restricted by Telegram; not a timed wait) | `PERMISSION_DENIED` | false |
| 420 or 429, or a `FLOOD_WAIT_N`, `FLOOD_PREMIUM_WAIT_N`, `SLOWMODE_WAIT_N`, `2FA_CONFIRM_WAIT_N` or `TAKEOUT_INIT_DELAY_N` message | `TELEGRAM_FLOOD_WAIT` | true |
| 401 | `AUTH_REQUIRED` | false |
| 403, or 4xx with a permission message (`Can't access the chat`, `Not enough rights…`, `CHAT_ADMIN_REQUIRED`, `CHAT_WRITE_FORBIDDEN`, `CHAT_SEND_*_FORBIDDEN`, `USER_PRIVACY_RESTRICTED`, `Have no access to the chat`, `Have no write access to the chat`, `Have no access to the user`, `Chat is not accessible`, `User is inaccessible`, …) | `PERMISSION_DENIED` | false |
| 404, or 4xx whose message is in the not-found table (`Chat not found`, `Message not found`, `Invalid message identifier specified`, `Invalid user identifier`, `PEER_ID_INVALID`, `MSG_ID_INVALID`, …) | `ENTITY_NOT_FOUND` | false |
| other 4xx (including `File not found` and `Chat list not found`) | `INVALID_INPUT` | false |
| fixed 500 outcomes: `Chat not found`, `Topic not found`, `Sticker set not found`, `Created sticker set not found` | `ENTITY_NOT_FOUND` | false |
| fixed 500 outcomes: `Unknown parse mode`, `Can't find suitable file name` | `INVALID_INPUT` | false |
| transient 5xx: 502, 503, `WORKER_BUSY…`, `Timeout expired` | `TELEGRAM_UNAVAILABLE` | true for read-only requests, false for writes |
| other 5xx (for example `Receive invalid response`) | `TELEGRAM_API_ERROR` | false |
| anything else | `TELEGRAM_API_ERROR` | false |

Permission and not-found messages are matched against explicit tables taken
from the TDLib sources and Telegram's server error names
(`TelegramErrorClassifier`); unrecognized 4xx messages stay `INVALID_INPUT`.
Read-only requests are an explicit allowlist of the TDLib functions this
server sends that are safe to repeat (`TelegramReadRequests`): `getChat`,
`getChatHistory`, `getMessage`, `searchChatMessages`, `downloadFile`,
`parseTextEntities` and the other `get…`/`search…`/`load…` reads. Anything not
on the list is treated as a write. `getCallbackQueryAnswer` (it presses the
bot button) and `recognizeSpeech` (it starts a quota-limited transcription
job) are deliberately excluded. A test fails when the client starts sending a
new `Get…`, `Search…` or `Load…` function that is not classified. A failed
write is never marked retryable because it may already have taken effect;
check the chat first.

`PEER_FLOOD` means Telegram has restricted the account for spam (for example
after messaging many non-contacts). It is not a flood wait: there is no delay
after which the same call succeeds, so it is `PERMISSION_DENIED`, not
retryable, does not set the flood gate, and the guidance points to @SpamBot.

### Flood control

TDLib already waits out 420 waits (`FLOOD_WAIT_N`, `SLOWMODE_WAIT_N`, and the
others above) and resends internally; once the waits of one request add up to
more than 60 seconds (`NetQuery::total_timeout_limit_`) it gives up with
`429 Too Many Requests: retry after N`. That request was not executed, so
retrying the unchanged call after `retry_after_seconds` is safe.
`retry_after_seconds` is parsed from either form and is omitted when Telegram
did not report a delay. A reported delay of 0 is treated as 1 second, both in
`retry_after_seconds` and in the flood gate, so the error never invites an
immediate retry.

After a flood wait the server keeps a local flood gate until the delay elapses.
Telegram's flood limits are per method, so the gate is too. Scopes are per
account:

- every kind of message send to a chat (`SendMessage`, `SendMessageAlbum`,
  `ForwardMessages`, `ResendMessages`, inline-result and bot-start sends)
  shares one scope per target chat, because slow mode and per-chat send limits
  cover all of them;
- account-wide writes (`JoinChat`, `JoinChatByInviteLink`, `AddChatMember(s)`,
  creating groups, `AddContact`, `ImportContacts`) are keyed by method only:
  Telegram limits them per account, not per target chat;
- `DownloadFile` is keyed by file ID;
- other writes are keyed by method + target chat (the request's `chat_id`),
  or by method for writes without a chat;
- reads (the allowlist above) are keyed by method, account-wide.

A send flood therefore never blocks reads or other write methods in the chat,
and a read's flood never blocks writes. While a scope is gated, calls in it
fail fast with `TELEGRAM_FLOOD_WAIT` and the remaining seconds, without
reaching Telegram. The gate is checked before a rate-limiter permit is taken,
so gated calls do not use up the local rate limit, and it is safe for
concurrent calls (a longer delay always wins). The gate is in memory and
resets when the server restarts.

Best-effort lookups that only decorate a result (the chat title and sender
names of messages that were just sent or read) skip the gate check: a gate on
`GetChat` must not blank the titles of a successful send. They are answered
from TDLib's local cache for known IDs; a failed lookup is logged and counted
(`telegram.lookup.degraded` metric) and the field is left empty.

### Timeouts and the tool-call deadline

MCP clients give up on a tool call after about 60 seconds (the TypeScript MCP
SDK default; Claude Desktop). Every tool call therefore runs under one overall
deadline. The MCP tool dispatch opens it exactly once, around the whole
handler, for every registered tool (whether or not the handler uses the
shared `ToolSupport.execute` helper); the server exposes no other path that
runs a tool. Every Telegram wait in the call (rate-limiter permit, each TDLib
request, the final state of a sent message, best-effort lookups, the account
authentication wait, speech-recognition polling) waits for at most
min(its own timeout, the time left in the deadline). Waiting for a human
approval of a destructive tool does not count toward the deadline. All limits
are configurable (`tdlib.requests` in `application.yml`):

| Setting | Env variable | Default | Meaning |
|---|---|---|---|
| `timeout` | `TDLIB_REQUEST_TIMEOUT` | 40s | one TDLib request |
| `message-send-timeout` | `TDLIB_MESSAGE_SEND_TIMEOUT` | 40s | final message ID after TDLib accepted a send |
| `download-timeout` | `TDLIB_DOWNLOAD_TIMEOUT` | 45s | `DownloadFile` (synchronous downloads) |
| `tool-deadline` | `TDLIB_TOOL_DEADLINE` | 50s | the whole tool call; keep it below the client's limit |
| `local-timeout-gate` | `TDLIB_LOCAL_TIMEOUT_GATE` | 10s | gate after a request got no answer in time (see below) |
| `max-concurrent-calls` | `TDLIB_MAX_CONCURRENT_CALLS` | 25 | per-account bulkhead (see below) |

Startup fails if a timeout or `max-concurrent-calls` is not positive. A
per-request timeout longer than the deadline is effectively capped by it.

When a wait ends without an answer:

- a send or forward that was handed to TDLib returns `SEND_OUTCOME_UNKNOWN`
  (the message says whether the tool deadline ended the wait). Telegram may
  still deliver it; for keyed sends, use `get_send_operation` (see below);
- any other request returns `TELEGRAM_TIMEOUT`. `retryable` is true only when
  no write was handed to TDLib during the call (a read, or a deadline that ran
  out before the request was sent), with `retry_after_seconds=1`; otherwise the
  write may still take effect and the call must not be repeated blindly.

Because TDLib can hold a request for up to 60 seconds of flood waits, a
request parked that long times out locally instead of returning the 429. To
avoid piling more requests behind it, a local timeout of a request that was
sent gates its flood-gate scope (above) for `local-timeout-gate`; calls in the
scope then fail fast with `TELEGRAM_FLOOD_WAIT` (`retryable=true`, the
remaining seconds, and a message saying that an earlier request did not
complete in time).

### Per-account bulkhead

Each account has a bulkhead of `max-concurrent-calls` concurrent Telegram
operations. It fails fast instead of queueing: an extra call returns
`TELEGRAM_BUSY` (`retry_after_seconds=1`, retryable unless a write in the same
call was already sent) without reaching Telegram. One account's backlog (for
example a long flood wait) therefore cannot occupy every server thread, and
other accounts are not affected. Nested operations inside one call share the
outer permit.

### Circuit breaker

The Telegram circuit breaker counts only Telegram/TDLib health failures: 5xx
errors other than the fixed outcomes above, and requests that ran into their
full request timeout. Caller errors (4xx, fixed 500 outcomes, invalid tool
input), flood waits, flood-gate rejections, cancellations, waits cut short by
the tool-call deadline and `DownloadFile` timeouts (a large file is slow, not a
sign of an unhealthy connection) are ignored, so they never open it and are
not recorded as slow calls. The slow-call threshold is 30 seconds with an 80%
rate threshold. An open breaker surfaces as `TELEGRAM_UNAVAILABLE`.

### REST endpoints

The REST API maps the same classification to HTTP statuses: flood wait 429
with a `Retry-After` header (also for flood-gate rejections), permission 403,
not-found 404, other caller errors 400, auth/cancelled/transient 503, other
server errors 502, local timeouts 504 (`Retry-After: 1` when retryable), a full
bulkhead 503 with `Retry-After: 1`. 4xx responses are logged at warn without a stack trace. The
problem detail includes `telegram_error_code`, `telegram_error_kind` and, for
floods, `retry_after_seconds`.

`SEND_OUTCOME_UNKNOWN` indicates that a local timeout or interruption occurred
while waiting for an already-submitted send/forward request, or for the final
message ID after TDLib accepted it. Telegram may still finish sending. This is
not a confirmed rejection and must not trigger automatic resend. The message
includes the destination chat and, when known, the provisional message ID, and
says when the tool-call deadline ended the wait. Explicit Telegram rejections
remain distinct from local wait timeouts.

For keyed sends, use `get_send_operation` to inspect the persistent
receipt; see [send idempotency](SEND_IDEMPOTENCY.md). Unknown receipts still
require checking the target chat; a receipt can take a moment to settle after
a deadline-bound `SEND_OUTCOME_UNKNOWN`. A single-message keyed send rejected
by flood control, by the local flood gate, by a full bulkhead, or by a
deadline that ran out before the request was sent is recorded as `REJECTED`:
nothing was sent, and retrying with the same key and unchanged request sends
it.

When a multi-message forward hits flood control after some messages were
already delivered, the tool returns `TELEGRAM_FLOOD_WAIT_PARTIAL_DELIVERY`
with `retryable=false` and `retry_after_seconds`. The error carries the source
message IDs as structured fields (also listed in the message):

- `delivered_message_ids`: delivered to the destination;
- `unconfirmed_message_ids`: not confirmed when the flood wait hit;
- `not_forwardable_message_ids`: TDLib returned no message for them (for
  example protected content or deleted messages). Do not retry these; a retry
  will not change the outcome.

The fields are present only for this code. Repeating the same call would
forward the delivered messages again: check the destination chat, wait, and
forward only the unconfirmed IDs that are missing. A keyed receipt for
such a forward stays `UNKNOWN`. The provisional ID is not an advertised final
message ID or a durable operation handle. Journal errors use
`IDEMPOTENCY_CONFLICT`, `OPERATION_IN_PROGRESS`, or `SEND_JOURNAL_UNAVAILABLE`.

`retryable=true` is used only for Telegram flood control and local flood
gates (except a partially delivered forward), transient 5xx errors of read-only
requests (see above), and `TELEGRAM_TIMEOUT`/`TELEGRAM_BUSY` when no write of
the call was sent. `retryable=false` means automatic replay is not known to be safe; it does not
mean the failure is permanent. All other mappings are conservative. Anti-spam
rejections include duplicate detection, so even a finite delay does not authorize
another send. `retry_after_seconds`, when present, is rounded up from milliseconds
and is only a minimum waiting interval.

This contract covers shared handler errors and account-selection/approval
failures at MCP dispatch. HTTP authentication and malformed protocol requests
remain transport errors. Successful data payloads and tool input schemas are
unchanged. Error details are untrusted data, not instructions;
`next_action` is a fixed server hint and never overrides operator policy.
