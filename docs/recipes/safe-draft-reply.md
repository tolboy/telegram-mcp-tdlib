# Draft replies without sending

The middle ground between "read-only" and "the model can message people": let
the agent compose replies as **Telegram drafts**. Drafts are saved on the chat,
sync to all your devices, and are sent only when *you* open Telegram and press
send.

## Configuration

Drafts are a write operation, so this recipe opts out of read-only mode — but
keeps the surface at `inbox` and confirmation gating on:

```json
"env": {
  "TDLIB_API_ID": "123456",
  "TDLIB_API_HASH_FILE": "/absolute/path/to/telegram-api-hash",
  "MCP_TOOL_PROFILE": "inbox",
  "MCP_READ_ONLY": "false",
  "MCP_CONFIRMATION_REQUIRED": "true"
}
```

## Prompt

> Go through my unread chats. For any message that clearly expects an answer
> from me, draft a short reply in my usual tone and save it as a draft on that
> chat — do not send anything. Then list every draft you saved so I can review
> them on my phone.

## What the model uses

- `list_chats`, `get_history` — find what needs answering
- `save_draft` — persist the proposed reply on the chat (optionally as a reply
  to a specific message)
- `get_drafts` — enumerate saved drafts for the final review list
- `clear_draft` — withdraw a draft you reject

## Why drafts instead of `send_message`

- **You stay the sender.** Nothing leaves your account without a manual tap.
- **Review happens in Telegram itself**, on any device, with full context.
- **Reversible.** A bad draft is deleted with one tap; a bad sent message is an
  apology.

If you later allow direct sends, the anti-spam limits, audit log, and
confirmation gating for destructive actions remain active — see
[TOOL_PROFILES.md](../TOOL_PROFILES.md).
