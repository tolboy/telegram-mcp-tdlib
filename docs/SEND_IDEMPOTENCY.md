# Recoverable text sends

`send_message` and `reply_to_message` accept an optional `idempotency_key`.
Use a new random key (for example a UUID) for each intentional send. Keep the
same key and parameters when recovering from a lost response. Keys are scoped
to the configured account label and shared between both tools.

```json
{"chat_id":"self","text":"Meeting notes","idempotency_key":"notes-2026-09-24-001"}
```

A keyed call returns a receipt as its `data` (and in the legacy text content):

```json
{
  "operation_id": "opaque digest",
  "chat_id": 123456,
  "status": "SENT",
  "message_id": 987654,
  "replayed": false
}
```

Calls without a key retain their existing message response and behavior. Keyed
calls return receipts instead of message bodies. Repeating a successful keyed
call returns its saved receipt with `replayed=true`, without another Telegram
send or anti-spam quota charge. Read-only, account selection, chat access and
configured confirmation/approval checks still apply. The fingerprint binds the
tool, resolved destination, text, formatting and applicable reply/topic/keyboard
parameters; omitted defaults and their explicit equivalents match.

Read a receipt without sending, including in the `reader` profile:

```json
{"chat_id":123456,"idempotency_key":"notes-2026-09-24-001"}
```

Pass those arguments to `get_send_operation`, with `account` when required.
Use the resolved numeric destination from the receipt where possible; usernames
can change ownership. The current chat allow-list is checked before journal
access, and a key for a different chat cannot reveal its receipt.

| Result | Meaning and recovery |
|---|---|
| `SENT` | A final Telegram message ID was saved. This is not a read receipt. |
| `UNKNOWN` | A reservation exists without a saved final result. Delivery may have happened; inspect the chat. Repeating the key will not send again. |
| `NOT_FOUND` | No saved reservation exists in this account's current journal. This says nothing about unkeyed sends or deleted/restored journals. |
| `OPERATION_IN_PROGRESS` error | Another caller holds the operation lock. Wait and query status. |
| `IDEMPOTENCY_CONFLICT` error | The key is bound to different parameters or a different destination. Do not invent another key to retry an uncertain send. |
| `SEND_JOURNAL_UNAVAILABLE` error | Storage is unavailable or damaged. Inspect storage and delivery; do not delete the record to force retry. |

An error after reservation conservatively leaves `UNKNOWN`, even when it may
have occurred before actual delivery. This favors avoiding duplicate messages.
There is no automatic reconciliation of late Telegram updates or automatic
retry of a reserved operation. `isError=false` means the receipt lookup worked;
only `status=SENT` confirms a stored send result.

## Storage and limits

The journal lives in `send-operations` under the platform application data
directory (`TELEGRAM_MCP_DATA_DIR` overrides it). Each account/key has a separate
OS lock and append-only JSONL record. The reservation is flushed before send;
the receipt is flushed after the final message ID is received. Multiple local
instances using this journal coordinate through the same lock. Keep the journal
directory consistent across daemon/client configurations.

Stored data includes hashed account/key filenames, request fingerprint,
destination ID and final message ID. Message bodies, raw keys and raw account
labels are not stored in records. Fingerprints are not encryption: protect the
directory as account metadata. POSIX account directories/files use owner-only
permissions; Windows inherits the application directory ACL.

Records are not automatically expired or removed on logout. Deleting them or
restoring an old backup removes replay protection. Account labels must retain
their identity; use a new label when replacing the underlying Telegram account.
Guarantees cover process interruption with the journal intact, not arbitrary
storage loss, power failure, remote filesystems or distributed exactly-once
delivery. The journal does not deduplicate sends made without a key, with a
different key, through another application, or from another journal directory.
Media, forwarding and scheduled-message tools do not support these keys yet.
