# MCP resources and subscriptions

The server can expose recent Telegram data as MCP resources and tell a
subscribed client when that data changes. The feature is **off by default**.

## Enabling

| Variable | Property | Default | Meaning |
|---|---|---|---|
| `MCP_RESOURCES_ENABLED` | `mcp.resources.enabled` | `false` | Register the resources and templates below. |
| `MCP_RESOURCE_SUBSCRIPTIONS` | `mcp.resources.subscriptions` | `true` | Advertise `resources.subscribe` and send `notifications/resources/updated`. Has no effect unless resources are enabled. |
| `MCP_RESOURCE_DEBOUNCE` | `mcp.resources.debounce` | `1s` | Changes to one URI inside this window produce one notification (0–60s). |
| `MCP_RESOURCE_MAX_SUBSCRIPTIONS` | `mcp.resources.max-subscriptions-per-session` | `50` | Subscriptions one HTTP MCP session may hold (1–1000). |

Why off by default: a host may attach, prefetch or index resources without the
model making a tool call, which widens where conversation text ends up. Tools
remain the primary interface; enable resources only for clients that use them
deliberately.

## URIs

| URI | Backing tool | Content |
|---|---|---|
| `telegram://{account}/chats` | `list_chats` (`limit` 50) | Recent chats of the account. Listed statically for every account configured at startup and also available as a template. |
| `telegram://{account}/chat/{chatId}` | `conversation_bundle` (`limit` 20) | The latest 20 messages of one chat. |
| `telegram://{account}/chat/{chatId}/message/{messageId}` | `get_messages` (`context_size` 1, filtered to the one message) | One message. |

URIs are canonical and strict: lowercase account label, decimal IDs without
leading zeros or `+`, no query, fragment, percent-encoding or trailing slash,
256 characters at most. Usernames are not accepted; resolve them with a tool
first. Every resource is `application/json` and is the backing tool's
`{data, meta}` envelope (for a message, `data` is the single message and
`meta.page` is dropped). Contents carry `_meta`
`{"io.github.tolboy/untrusted-content": true}`; treat the text as untrusted
input, exactly like tool output.

`resources/list` is static and not filtered per caller (the SDK cannot filter
it). It exposes account labels, which every tool's `account` enum already
exposes. The capability is advertised with `listChanged: false`.

## Security model

- A read runs the backing tool's MCP handler, so the whole tool pipeline
  applies: caller identity, tool profile and allow/deny lists, read-only mode,
  key account scope, the permissions file (including chat-scoped grants and
  their result filtering), chat allow/deny lists, deadlines and audit. A
  resource cannot reach a chat its backing tool could not.
- Denied, hidden, unknown and malformed URIs all fail with the same JSON-RPC
  error: `-32002 Resource not found` with `data.uri`. Other tool failures are
  `-32603 Resource read failed: <CODE>` without the tool's message.
- `resources/subscribe` over HTTP is checked before the SDK sees it, with the
  same rules as a read: a URI the caller could not read is refused with the
  same `-32002` error and nothing is recorded. Batched subscribe/unsubscribe
  requests are refused (`-32600`).
- Each subscription is recorded with the caller's authentication. Before every
  notification the server re-checks that identity; a revoked grant, a changed
  permissions file or an expired OAuth token stops notifications.
- Sessions stay bound to the principal that created them (the existing
  session-principal filter); the subscription registry also never moves a
  session's subscriptions to another principal.
- Notifications carry only the URI. The client must read the resource again,
  which goes through the checks above.
- A `chats` subscriber is notified only when at least one chat that changed in
  the debounce window would appear in that subscriber's `chats` list, so a
  client limited to some chats learns nothing about activity elsewhere. The
  check uses the list's own tool, `list_chats` (its visibility, `list_chats`
  grant scope and the chat allow-list); a client allowed only `list_chats`
  still gets `chats` notifications. `chat` and `message` notifications are
  checked against `conversation_bundle` and `get_messages`. Up to 256 distinct
  changed chats are remembered per pending URI; later ones in the same window
  are ignored for this check (fails closed). Coverage gaps are not
  chat-specific and reach every permitted `chats` subscriber.

## Change mapping

Notifications are driven by the change journal (the source of
`changes_since`/`wait_for_changes`), so they cover what the journal records:

| TDLib update | Journal kind | Resources notified |
|---|---|---|
| `UpdateNewMessage`, `UpdateMessageSendSucceeded` | `new` | chat, chats |
| `UpdateMessageContent` | `content_changed` | chat, message |
| `UpdateMessageEdited` | `edited` | chat, message |
| `UpdateDeleteMessages` (permanent only) | `deleted` | chat, message, chats |
| Client attach, connection not ready, journal overflow | `coverage_gap` | every subscribed URI of the account (HTTP); the account's chats URI (stdio) |

Updates are delivered after the journal commits them. Bursts are coalesced per
URI within the debounce window; at most 10,000 URIs are pending, and further
changes are dropped with one warning until the backlog drains.

## Transports and limits

- **HTTP (Streamable HTTP):** notifications go over the session's GET SSE
  stream. Subscriptions are forgotten on a successful `DELETE`, when the
  transport answers 404 for the session, and after 24 hours without requests.
  At most 4,096 sessions hold subscriptions. Requests for an unknown session
  are normally answered 404 (and a foreign session 403) by the session-principal
  filter before they reach subscription tracking, so in practice a session that
  disappears without a `DELETE` keeps its entries until the idle timeout; the
  SDK drops notifications to unknown sessions silently.
- **stdio:** the SDK tracks subscriptions itself. The single local identity is
  re-checked before each notification. A coverage gap refreshes only the chats
  URI because the SDK's subscription list is not readable.
- While subscriptions are active, an authenticated `/mcp` POST with a body
  over 1 MiB is refused with HTTP 413 and a JSON-RPC `-32600` error before it
  reaches the SDK, whether it declares a `Content-Length` or is chunked. A
  body the filter cannot read whole could otherwise carry an unchecked
  subscribe past the authorization check and the per-session limit.

## Not covered

- Chat order, title, read state and other chat-level updates
  (`UpdateChatPosition`, `UpdateChatTitle`, ...) do not notify `chats`.
- `listChanged` is not supported; accounts registered after startup are not in
  `resources/list` but resolve through the `chats` template.
- Export jobs and media are not resources.
