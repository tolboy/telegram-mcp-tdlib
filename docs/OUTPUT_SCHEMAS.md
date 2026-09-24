# Typed MCP output contracts

The following tools advertise explicit JSON Schemas for `structuredContent`:

- `send_message` and `reply_to_message`: a Telegram message for calls without
  an idempotency key, or a delivery receipt for keyed calls.
- `get_send_operation`: a delivery receipt.
- `export_chat_history`: bounded messages, counters, date window and completeness.
- `search_public_messages`: per-chat results/failures and timeout coverage.

The envelope remains `{ "data": ..., "meta": ... }`. Its `data` schema permits
the tool's success shapes or the shared `{ "error": ... }` shape. Both success
and error responses retain their previous text content, flags and structured
field names. Other tools retain the existing generic data schema.

Schemas describe field types, required properties, status values and known
nested message/poll structures. A `SENT` receipt requires a positive message ID;
`UNKNOWN` and `NOT_FOUND` require a null message ID. Message dates accept both
existing Jackson timestamp representations (numeric epoch seconds or ISO text).
Unknown properties are rejected in the typed shapes, so extending their wire
format requires updating the schema and its tests together.

Output variants use inline `anyOf`; input schemas remain unchanged and keep the
existing conservative client-compatibility profile. No new runtime validation
gate is inserted before or after Telegram writes. Output validation runs in
tests with the MCP SDK's JSON Schema validator against real handler results,
including policy errors, incomplete research results and receipt replays.
These tests are not a claim of acceptance by every desktop client's UI/version.

Schemas validate structure, not delivery or search completeness. Inspect receipt
`status` and research `complete`/`completion_scope` fields. A valid structured
response does not prove that Telegram delivered a message or exhausted a search.
