# Find a lost message

"Someone sent me an address / file / decision weeks ago and I cannot find it."
Telegram's built-in search needs the right keyword; an agent can search several
ways, read around the hits, and reason about which one you meant.

## Configuration

The `reader` profile is always non-mutating — the safest possible surface:

```json
"env": {
  "TDLIB_API_ID": "123456",
  "TDLIB_API_HASH_FILE": "/absolute/path/to/telegram-api-hash",
  "MCP_TOOL_PROFILE": "reader",
  "MCP_READ_ONLY": "true"
}
```

## Prompt

> Sometime in the last two months someone sent me the address of a co-working
> space in Lisbon — possibly in a group chat, possibly as a forwarded message.
> Search my chats for it. If you find candidates, show each with surrounding
> context and a link so I can jump to it.

## What the model uses

- `search_global` — keyword search across all chats
- `search_messages` — narrow the search inside a promising chat
- `get_message_context` — read the messages around a hit to confirm it
- `get_message_link` — hand back a clickable t.me link
- `resolve_username`, `search_contacts` — when you remember *who* but not *where*

## Variations

- **From a link:** paste a t.me link and ask "what was this about?" —
  `message_from_link` resolves it to the actual message.
- **Media hunt:** "find the PDF invoice sent to me in March" — search by chat
  and read message metadata; `download_media` is available outside read-only
  mode if you need the file itself.
