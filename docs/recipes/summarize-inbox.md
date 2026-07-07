# Summarize your inbox

Get a digest of what happened across your chats — without giving the model any
ability to send, edit, or delete.

## Configuration

```json
"env": {
  "TDLIB_API_ID": "123456",
  "TDLIB_API_HASH_FILE": "/absolute/path/to/telegram-api-hash",
  "MCP_TOOL_PROFILE": "inbox",
  "MCP_READ_ONLY": "true"
}
```

With `MCP_READ_ONLY=true` the write tools (`send_message`, `delete_message`, …)
are not in the tool list at all.

## Prompt

> Summarize my last 20 Telegram conversations. For each: who it is, what the
> latest exchange was about, and whether anything is waiting on a reply from me.
> Group by urgency. Do not send or modify anything.

## What the model uses

- `list_chats` — enumerate recent chats with unread filters
- `get_history` — pull recent messages per chat
- `get_drafts` — spot half-written replies you forgot about

## Variations

- **Unread only:** "Only look at chats with unread messages."
- **One folder:** "Only chats in my Work folder" — the model can read folder
  rules via `list_chat_folders` / `get_chat_folder`.
- **Daily digest:** run the same prompt each morning; the server's anti-spam
  limits and audit log apply regardless.

## Safety notes

Message content from Telegram is marked as untrusted before it reaches the
model, and in this configuration there is no tool a prompt-injection payload
could escalate to. See [SECURITY.md](../../SECURITY.md).
