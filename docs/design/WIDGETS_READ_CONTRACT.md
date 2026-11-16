# HTML widgets: read-only contract (design)

Status: design draft, not implemented. Scope: steps 2 and 3 of the widget plan.
Source of truth for field names: `td/generate/scheme/td_api.tl` on TDLib master
(TDLib 1.8.70). Our current build (`tdlight 3.5.9+td.1.8.68`) predates widgets,
so nothing here can ship until the TDLib upgrade (step 1) lands.

## Background

Bot API 10.4 (2026-11-09) added HTML widgets: a message whose content is one
self-contained HTML document (inline CSS and JS) that Telegram apps run in a
sandbox with no network access. Only bots can send widgets. On 2026-11-13
Telegram stopped ordinary bots from sending them after security reports, so
live samples may be rare for a while. Existing widget messages can still be
read.

For a user account, TDLib exposes:

| TDLib object / function | Use here |
|---|---|
| `messageWidget width:int32 height:int32 html:string public_data:string chat_data:string widget_id:int64 caption:formattedText show_caption_above_media:Bool is_copy:Bool can_send_queries:Bool can_send_background_queries:Bool` | message content mapping |
| `getWidgetPublicData chat_id:int53 message_id:int53 = WidgetPublicData` | `get_widget_public_data` |
| `widgetPublicData data:string poll_period:int32` | its result |
| `updateMessageContent` | data changes (already journaled as `content_changed`) |

Out of scope: `getWidgetQueryAnswer` (step 4, it talks to the bot), and all
bot-only API (`inputMessageWidget`, `inputMessageSharedWidget`,
`answerWidgetQuery`, `editMessageWidgetData`, `editSharedWidgetData`,
`updateNewWidgetQuery`). TON wallet query types are excluded on purpose.

## 1. `widget` content type

### Mapper (`TdLibMessageMapper`)

- `contentType` becomes `"widget"` automatically (class-name rule).
- `mediaType` gets an explicit `is TdApi.MessageWidget -> "widget"` branch
  instead of falling into `"other"`.
- `text`: the caption if non-empty, else `"[Widget]"`. Never the HTML.
- New nullable field `widget: TelegramWidget?` on `TelegramMessage`, set only
  for `MessageWidget`.

### `TelegramWidget` (summary, returned everywhere a message is returned)

| JSON field | TDLib field | Type | Notes |
|---|---|---|---|
| `width` | `width` | integer or null | null when TDLib gives 0 (full bubble width) |
| `height` | `height` | integer or null | null when TDLib gives 0 (not fixed) |
| `widgetId` | `widget_id` | string or null | decimal int64 string (JS precision), null when 0 (not shared) |
| `isShared` | derived | boolean | `widget_id != 0` |
| `isCopy` | `is_copy` | boolean | |
| `showCaptionAboveMedia` | `show_caption_above_media` | boolean | |
| `canSendQueries` | `can_send_queries` | boolean | informational; we never send queries in steps 2–3 |
| `canSendBackgroundQueries` | `can_send_background_queries` | boolean | informational |
| `htmlByteCount` | `len(utf8(html))` | integer | |
| `htmlSha256` | `sha256(utf8(html))` | string | lowercase hex; lets a client detect a repeated widget without pulling the HTML |
| `publicDataByteCount` | `len(utf8(public_data))` | integer | |
| `chatDataByteCount` | `len(utf8(chat_data))` | integer | |

The caption stays in `text` (as for photos); its entities follow the existing
caption rules. The summary deliberately carries **no HTML and no data bodies**,
so history, search, exports and new-message events cannot pull up to 32 KB of
executable code into a model's context by accident. `ToolOutputSchemas` gets
a nullable `widget` object with exactly these fields.

## 2. `get_widget` tool

Full widget of one message.

- Permission action: `read`. Read-only annotations (`readOnlyHint: true`,
  `openWorldHint: false`). Listed under the `getMessages` method group in
  `ToolContractCatalog`. Subject to the chat allow-list like `get_messages`.
- Input:

```json
{
  "chat_id": "123456789",
  "message_id": 42,
  "include_html": false,
  "max_html_bytes": 8192
}
```

| Param | Type | Default | Rules |
|---|---|---|---|
| `chat_id` | string or integer | required | same resolver as `get_messages` |
| `message_id` | integer | required | strict integer (`ToolNumbers`) |
| `include_html` | boolean | `false` | HTML is opt-in |
| `max_html_bytes` | integer | `8192` | 1..32768; HTML longer than this is cut at a UTF-8 boundary and `htmlTruncated` is true |

- Output `data`:

| Field | Notes |
|---|---|
| `chatId`, `messageId` | |
| `widget` | the `TelegramWidget` summary above |
| `caption` | caption text |
| `publicData` | string, ≤1024 bytes by Telegram limit, returned in full |
| `chatData` | string, ≤1024 bytes, returned in full |
| `html` | present only when `include_html` is true |
| `htmlTruncated` | boolean, present with `html` |

- Errors: message not found uses the existing `MESSAGE_NOT_FOUND`; a
  non-widget message returns `INVALID_ARGUMENT` with
  `next_action: "Check contentType; only widget messages have widget data."`.
  When the running TDLib has no widget support (class absent), the tool is
  not registered at all, rather than failing at call time.

## 3. `get_widget_public_data` tool

Current public (shared) state of a widget, from `getWidgetPublicData`.

- Permission action: `read`; read-only annotations; chat allow-list applies.
- Input: `chat_id`, `message_id` (same rules as above). No other params.
- Output `data`: `chatId`, `messageId`, `publicData` (string),
  `pollPeriodSeconds` (from `poll_period`), `nextPollAt` (ISO-8601 instant,
  now + poll_period), `cached` (boolean).
- Rate limit: TDLib says the function is for a widget that is visible and was
  asked to poll. We honour `poll_period` per `(account, chat_id, message_id)`:
  a call before `nextPollAt` returns the last result with `cached: true`
  instead of calling TDLib. Cache entries expire after `max(poll_period, 60s)`
  and the cache is bounded (Caffeine, 1,000 entries).
- Scheduled messages are rejected with `INVALID_ARGUMENT` (TDLib forbids them).
- TDLib 502 or 400 errors map through the existing `ToolError.from`.

## 4. HTML as untrusted content

The HTML is executable code from a third party and a direct prompt-injection
channel. Rules:

1. **Never executed or rendered** by the server, and never fetched further
   (no resolving of URLs inside it).
2. **Opt-in only**: only `get_widget` with `include_html: true` returns it;
   summaries carry byte count and hash only.
3. **Size-capped** by `max_html_bytes` (default 8 KB, hard max 32 KB).
4. **Standard envelope**: the result goes through `ToolSupport` as usual, so it
   carries `meta.untrustedTelegramContent: true`, the
   `io.github.tolboy/untrusted-content` `_meta` flag, and the
   `UntrustedContentNormalizer` escaping of invisible/bidi characters. No
   textual prefix is added (it would break the text/structured-content match
   that `scripts/mcp-result-smoke.py` checks).
5. **Typed marker**: next to `html` the result includes
   `"htmlMediaType": "text/html; executable=true"` so a client can decide not
   to display or interpret it as instructions.
6. `publicData` and `chatData` are bot-controlled strings (often JSON); they
   are returned as plain strings, never parsed, and get the same envelope.
7. `export_chat_history` and `tasks` exports include the summary only, never
   the HTML.

## 5. Search and history filter

TDLib has **no** `searchMessagesFilterWidget`, so filtering is client-side.

- `get_history` and `search_messages` get an optional input
  `content_types: string[]` (values are our `contentType` strings, e.g.
  `["widget"]`). Unknown values are an `INVALID_ARGUMENT` error.
- Matching happens after mapping; pagination still uses the TDLib message id
  offset. To keep cost bounded, the server scans at most
  `limit × 5` messages (max 500) per call. The result then carries
  `scanned` and `nextOffset` so the caller can continue; `hasMore` stays
  accurate.
- In `search_messages`, the text query still applies to the caption; an empty
  `query` with `content_types: ["widget"]` is allowed and lists widget
  messages.
- `search_global` is unchanged (no reliable server-side filter).

## 6. Tests

- Mapper unit tests with a constructed `TdApi.MessageWidget`: summary fields,
  0 → null for size and `widget_id`, hash and byte counts, `text` fallback.
- `get_widget`: default (no HTML), `include_html`, truncation at a multibyte
  boundary, non-widget message, allow-list denial.
- `get_widget_public_data`: cache hit before `poll_period`, refresh after,
  scheduled message rejection.
- Filter: mixed page with widgets, scan cap, `nextOffset` continuation.
- A fixture JSON of a real `messageWidget` once we can capture one (live
  capture is blocked while Telegram disallows sending).
- Output-schema snapshot tests updated for the new `widget` field.

## Open questions

- Should `get_widget_public_data` be merged into `get_widget` as an
  `refresh_public_data` flag? Kept separate here because it calls Telegram and
  has its own rate rule.
- Default `max_html_bytes`: 8 KB keeps most widgets whole (4 KB compressed
  usually means 10–20 KB raw); confirm against real samples.
