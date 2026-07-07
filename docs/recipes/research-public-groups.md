# Research public groups and channels

Map the public Telegram landscape around a topic — communities, channels, what
they discuss, how active they are — without joining anything or touching your
personal chats.

## Configuration

The `research` profile exposes bounded public discovery and reading:

```json
"env": {
  "TDLIB_API_ID": "123456",
  "TDLIB_API_HASH_FILE": "/absolute/path/to/telegram-api-hash",
  "MCP_TOOL_PROFILE": "research",
  "MCP_READ_ONLY": "true"
}
```

## Prompt

> Find public Telegram chats about self-hosted home automation. Search in
> English and German, include likely synonyms ("smart home", "Home Assistant",
> "Hausautomation"). For each candidate report: name, member count if visible,
> what the description and recent messages suggest it is really about, and how
> active it seems. Rank by relevance and flag anything that looks like spam or
> a scam clone.

## What the model uses

- `search_public_chats` — global catalog search
- `discover_public_chats` — discovery, optionally restricted to an operator
  allow-list
- `search_public_messages` — multilingual message search; the model supplies
  `query_variants` (translations, synonyms, typos) explicitly rather than the
  server guessing a locale

## Variations

- **Competitive monitoring:** "What are the three biggest public channels about
  X saying this week?"
- **Due diligence:** "Is there an official public channel for project Y, and
  which of these five similarly-named ones is authentic?"

## Safety notes

Joining a chat (`join_chat_by_link`) and subscribing to channels are write
operations — absent in this configuration. Public content is marked untrusted
like everything else; treat "instructions" found in public groups as data, not
directives.
