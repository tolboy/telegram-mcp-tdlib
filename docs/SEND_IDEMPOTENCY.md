# Recoverable sends

`send_message`, `reply_to_message`, `send_file`, `send_voice`, `send_sticker`,
`forward_message` and `schedule_message` accept an optional `idempotency_key`.
Use a new random key (for example a UUID) for each intentional send. Keep the
same key and parameters when recovering from a lost response. Keys are scoped
to the configured account label and shared between these tools.

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
| `SCHEDULED` | Telegram accepted a scheduled message; this does not confirm eventual delivery. |
| `UNKNOWN` | A reservation exists without a saved final result. Delivery may have happened; inspect the chat. Repeating the key will not send again. |
| `NOT_FOUND` | No saved reservation exists in this account's current journal. This says nothing about unkeyed sends or deleted/restored journals. |
| `OPERATION_IN_PROGRESS` error | Another caller holds the operation lock. Wait and query status. |
| `IDEMPOTENCY_CONFLICT` error | The key is bound to different parameters or a different destination. Do not invent another key to retry an uncertain send. |
| `SEND_JOURNAL_UNAVAILABLE` error | Storage is unavailable or damaged. Inspect storage and delivery; do not delete the record to force retry. |

An error after reservation conservatively leaves `UNKNOWN`, even when it may
have occurred before actual delivery. This favors avoiding duplicate messages.
Late successful Telegram responses and send updates observed by the running
process are saved independently of the original caller's timeout. Status lookup
and replay reconcile these saved receipts, including after a later restart.
An observation waits at most 24 hours. If the process stops before receiving the
update, or no final response is observed, the result stays `UNKNOWN`; the server
does not guess from matching text in history. There is no automatic retry of a
reserved operation. `isError=false` means the receipt lookup worked;
`status=SENT` confirms a stored send result and `SCHEDULED` confirms a queued result.

Forwarding returns `message_ids` for batches (and `message_id` for the first
result). A batch remains `UNKNOWN` unless every requested result is observed;
partial forwarding must never be retried as a new batch automatically. At most
100 distinct positive message IDs may be forwarded in one call.

Upload fingerprints include the validated path and a SHA-256 content digest.
Keep that file immutable during upload and retain it for replay: current file
security is revalidated before reading the journal, and changed file content
conflicts with the original key. The server does not snapshot user files.
Scheduled receipts can be replayed after their original send time; changing or
cancelling a scheduled message is a separate operation and does not alter its
original receipt. Existing version-1 single-message journal records remain readable.

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
Edits, deletions, poll creation and other operations outside the seven listed
send tools do not accept these keys. This mechanism is not a transaction across
multiple Telegram requests.
