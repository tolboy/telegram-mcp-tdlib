# Compact tasks, persistent exports and changes

All four tools use the usual account selection, read permission, chat restrictions,
typed result envelope and untrusted-content normalization. None sends messages,
marks them read or downloads media. `reader` exposes all four; `inbox` exposes
the three compact tools; `research` exposes conversation, changes and export.

## Compact reads

- `inbox_snapshot {"limit":50,"max_chars":8000}` returns allowed unread chats
  from a bounded recent-chat prefix. `truncated=true` deliberately does not claim
  that this prefix is the whole inbox. Titles are shortened to 160 characters.
- `conversation_bundle {"chat_id":"self","limit":20,"max_chars":8000}` returns
  IDs, sender names, text snippets and media types. Pass `nextMessageId` back as
  `from_message_id` to read older messages. Snippets start at 500 characters and
  may shrink to fit the budget; `contentTruncated` marks shortened fields.
  Use `get_messages` to retrieve a selected full message. A nonempty page is
  bounded (`truncated=true`), including when Telegram returns fewer than `limit`.
- `changes_since {"limit":100,"max_chars":8000}` returns observed change metadata.
  Pass `nextCursor` back as `cursor`; keep paging while `hasMore` is true.

`max_chars` (512–32000, default 8000) bounds the serialized **normalized data JSON**,
including escaping and its data-level metadata. It is not a token estimate or
a bound on the entire MCP transport frame, which also contains the envelope
and a text representation. Counts bound Telegram work independently of this budget.

## Persistent history export

Use `export_job` with these arguments, retaining the returned `job.jobId`:

```json
{"action":"start","chat_id":"self"}
{"action":"resume","job_id":"<returned UUID>"}
{"action":"status","job_id":"<returned UUID>"}
{"action":"page","job_id":"<returned UUID>","page":0}
{"action":"cancel","job_id":"<returned UUID>"}
{"action":"delete","job_id":"<returned UUID>"}
```

Jobs are **pull-driven**. Each `resume` fetches at most 100 messages, commits a
page, then atomically advances its checkpoint. Continue until `COMPLETE` or a
reported `reason` requires attention. `PAUSED` means ready for another resume;
there is no unattended background worker. A short page is not exhaustion; an
empty history page is. Cursors request messages strictly older than the last
committed message. A nonadvancing cursor remains paused and is never silently
declared complete. Telegram failures leave the checkpoint unchanged.

`status` and repeated `resume` on completed jobs do not fetch Telegram history.
`page` reads a committed page by zero-based index. Cancellation is terminal but
retains saved pages; deletion removes the local job and its pages. Neither action
changes Telegram. Limits: 100 retained jobs per account/client and 10000 pages per
job. Delete old jobs when the limit is reached.

Jobs are scoped to the selected account and authenticated client identity.
Every operation rechecks current chat access, including reading old pages. A new
process with the same data directory, account label and identity can resume.
Changing between STDIO (`stdio`) and the default HTTP key (`mcp-client`) changes
ownership. Clients sharing that key share job ownership; use named client keys
for separate identities.

Exports capture accessible history **at each page fetch**, not an atomic snapshot
of Telegram. Later edits/deletions can change previously read messages; new
messages arriving above the initial cursor require a new read or reconciliation.
Media payloads are not exported. Process-crash recovery uses flushed files and
atomic replacement; power-loss durability depends on the filesystem. An
unacknowledged page may be fetched again after a crash before checkpoint commit.
Uncommitted page files are ignored and replaced on the next resume.

## Change journal coverage and storage

The TDLib runtime and interactive authentication runtime record new messages,
content changes, edits and permanent deletions. Records contain IDs, kind and
observation time, not message bodies. They describe **locally observed updates**;
Telegram may replay updates, so consumers must tolerate duplicates. Message
changes can be hydrated with `get_messages`; deleted messages may be unavailable.
Other updates (reactions, read receipts, chat properties) are outside this journal.

Recording never delays Telegram: TDLib delivers updates on the same thread as
every request result, so update handlers only enqueue (up to 20000 pending
records). A single background writer appends batches and flushes them to disk;
`changes_since` first waits briefly for records observed before the call.

Restart/reconnect markers, a full queue, a failed journal write and retention
loss produce `gap=true`. A marker is written after the loss it reports, so any
reader that continues past the loss sees it. Reconcile affected history when a
gap appears; offline deletions or other missed events cannot be reconstructed
reliably from this journal alone.
Each account retains its latest 10000 records. The cursor includes a journal
epoch; a cursor from another account or a reset journal is rejected. A page can
be empty after access filtering while `hasMore=true`; its cursor still advances.

Local plaintext state lives under `TELEGRAM_MCP_DATA_DIR` (or the standard app
data directory): `changes/<account>.jsonl` and `exports/<owner-hash>/`. The
journal is append-only JSON lines, compacted to the retained records once it
doubles; an append interrupted by a crash is discarded on the next start. These
directories use owner-only permissions/ACLs. Locks serialize writers across
processes, and malformed state fails closed rather than being silently reset.
Back up or remove local data only with the server stopped. Keep the data directory
on a local filesystem supporting locks and atomic replacement.
