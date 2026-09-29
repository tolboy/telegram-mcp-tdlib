# Typed MCP output contracts

All 115 tools advertise explicit JSON Schemas for `structuredContent`. The
catalog in `ToolContractCatalog.kt` binds each tool to its wire DTO or named
response shape. DTO fields, nullability, collections and enums are expanded
from their declared Kotlin types; map-shaped responses are registered explicitly.
An inventory test prevents a new tool from silently missing its contract.

The envelope remains `{ "data": ..., "meta": ... }`. Its data schema permits
success shapes or the shared `{ "error": ... }` shape. Existing text content,
field names and unkeyed send results remain compatible. Account selection and
approval failures now return the same structured error envelope with isError=true;
HTTP authentication and protocol errors remain transport-level errors.

Schemas describe required properties, nested fields, status values and enum
choices. SENT and SCHEDULED receipts require positive message IDs; UNKNOWN and
NOT_FOUND require a null ID. Dates accept the existing Jackson ISO and numeric
representations. Objects reject undeclared properties, except explicitly typed
maps such as manifest tool groups. Schema changes must accompany wire changes.

## List and pagination contract

At MCP dispatch, successful read tools returning an array also receive meta.page:

```json
{"returned_count":20,"complete":null,"continuation_parameters":["from_message_id"],"scope":"returned_items"}
```

This metadata does not change legacy text or data arrays. The count describes
returned items after filtering, not source cardinality. A null complete means
exhaustion was not established, even for an empty or short page. Continuation
parameters list existing pagination inputs advertised by that tool (offset or
from_message_id); an empty list means no continuation is exposed. Keep other
query parameters unchanged and deduplicate boundary IDs when advancing a
message cursor. No next cursor is fabricated from a filtered list. Persistent
resume tokens and full-export jobs belong to stage 6 of the implementation plan.
Research tools retain their more precise completeness fields within data.

## Verification boundary

Handler tests validate actual success and error results with the MCP SDK's JSON
Schema validator. Catalog-wide negative checks reject arbitrary objects and
accept the shared error shape. No runtime output rejection is added after writes:
a serialization-contract bug must not invite another Telegram send. These checks
cover mocked Telegram responses, not every desktop client's UI or live TDLib.
