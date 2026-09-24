# Research result completeness

`export_chat_history` is a bounded selection, not a full archive. Existing
message and counter fields remain available. The following additive fields
describe whether the requested sources were exhausted within the date window:

- `complete`: true only when every requested source reached an empty page or
  crossed the lower date bound and no collected messages were dropped.
- `completion_scope`: `requested_sources`. With `include_history=false`, this
  covers only the supplied search terms, not all messages in the chat. Even a
  complete result describes Telegram's accessible results, not an immutable
  snapshot or a guarantee that deleted/inaccessible messages were retrieved.
- `partial_reasons`: zero or more of `result_limit`, `page_limit`, and
  `cursor_stalled`. Limits and stalled pagination must not be interpreted as
  proof that no other matching messages exist.
- `truncated`: the inverse of `complete`, including cases where completeness
  could not be established; it does not promise additional messages exist.
- `scanned_count`: messages fetched across all source pages before date
  filtering and deduplication. It can count the same message more than once.

Search terms run first; history fills remaining capacity when enabled. A
short nonempty page is not sufficient evidence of exhaustion. Requests remain
bounded to 12 history pages and 8 pages per search term. An export with no
source (`include_history=false` and no search terms) is rejected.

No resumable cursor is advertised by this tool yet. A caller must not treat
the last returned message as a cursor covering all independent search terms.

## Public message search

`search_public_messages` retains successful chat results even if another query
fails. Each completed chat now includes `complete` and `partial_reasons`.
Successful chat dispatches additionally include `failed_queries` (query and
error), `scanned_count`, and `truncated`. Reaching a query's result cap or
dropping merged hits sets `truncated=true` and the `result_limit` reason.

Top-level `complete` is false for any failed/capped chat or timeout.
`completion_scope=requested_query_pages` means completion of the bounded query
pages requested by this tool, not exhaustive search of the chat history.
`incomplete_chats` identifies unfinished inputs by zero-based index and chat
identifier after timeout. Existing `timed_out` and `timeout_ms` fields remain.
Completed chats retain input order, as do per-query failure entries.

A successful empty search has an empty `failed_queries` list. If every variant
fails, the chat remains visible with `complete=false` and `query_failed`; it
must not be treated as evidence of no matching messages. Chat resolution and
access errors are also incomplete. Null chat inputs are rejected up front.

Blocking resolution/search waits are interruptible on coroutine cancellation.
This bounds the local wait; cancellation does not revoke a request already
submitted to Telegram.
