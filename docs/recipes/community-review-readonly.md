# Read-only community health check

You moderate a group or channel. Get a report on what needs attention — spam,
unanswered questions, heated threads — while the model provably cannot ban,
delete, or post.

## Configuration

The `community-admin` profile carries the moderation surface; read-only mode
strips every mutating tool from it before the model sees the list:

```json
"env": {
  "TDLIB_API_ID": "123456",
  "TDLIB_API_HASH_FILE": "/absolute/path/to/telegram-api-hash",
  "MCP_TOOL_PROFILE": "community-admin",
  "MCP_READ_ONLY": "true"
}
```

## Prompt

> Review the last 200 messages in "Kotlin Hangout". Report: (1) questions nobody
> answered, (2) messages that look like spam or scam patterns, (3) threads that
> got heated and may need a moderator note, (4) the most useful message worth
> pinning. Do not take any action — this is a report for me.

## What the model uses

- `get_history`, `get_message_context` — read the discussion
- `get_chat`, `get_participants` — group metadata and member context
- `list_topics` — per-topic review in forum supergroups
- `get_pinned_messages` — what is already pinned

## Graduating to actions

When you trust the reports, enable writes deliberately:

1. Set `MCP_READ_ONLY=false` — moderation tools (`ban_user`, `pin_message`,
   `delete_message`, …) appear.
2. Keep `MCP_CONFIRMATION_REQUIRED=true` (the default): destructive actions
   still require an explicit `"confirmed": true` on each call, and everything
   is audit-logged.
3. Consider a chat allow-list so the surface only covers the communities you
   moderate.
