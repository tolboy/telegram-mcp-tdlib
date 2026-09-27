# Client, account, action and chat permissions

Set `MCP_PERMISSIONS_FILE` to a reviewed JSON file and restart the server. Relative
paths resolve below the application data directory. With no path configured,
existing permissions are unchanged. A missing, malformed or invalid configured
file prevents startup; an empty grants list denies all account operations.

```json
{
  "version": 1,
  "grants": [
    {"client":"researcher","account":"work","actions":["read","download"],"chat_ids":[-1001234567890]},
    {"client":"writer","account":"work","actions":["mutate"],"chat_ids":[-1001234567890]}
  ]
}
```

Client identity is the named API-key ID, the configured OAuth principal claim,
`stdio` for STDIO, `local-dev` for keyless loopback HTTP, or `mcp-client` for the
legacy single API key. Never put API keys, tokens or phone numbers in this file.
STDIO has one local identity; use separate server configurations if different
STDIO processes need different grants.

Account labels are exact normalized labels; `*` matches all configured accounts.
Client wildcards are not supported. Rules for the same client/account/action
are additive. Existing API-key/OAuth account restrictions and the global chat
allow-list still intersect with grants. `chat_ids` must be explicit: `null` means
unrestricted chats and permits account-wide tools; `[]` grants no chat access.
Tools without reviewed chat scoping fail closed for restricted grants (including
account profile, contact management, chat folders and creating unknown chats).
Forwarding requires permission for both source and destination under its action.

| Action | Operations |
|---|---|
| `read` | Read tools, including delivery status |
| `download` | `download_media`, which writes the account's local cache |
| `mutate` | Telegram state changes and sends |
| `quota` | `transcribe_voice_note` |
| `policy` | `register_internal_chat`, which changes anti-spam policy |

Actions do not imply one another. A read grant does not allow downloads; a mutate
grant does not grant transcription or read access. Server read-only mode, tool
profiles, exact allow/deny filters, confirmation, human approval, file security
and anti-spam checks continue to apply. For read-only downloads, also enable
`MCP_READ_ONLY_ALLOW_DOWNLOADS=true`. New write tools inherit the mutate action;
new tools are denied for chat-scoped grants until reviewed.

The MCP tool list remains the shared configured surface. Permissions are checked
on every invocation before approval and Telegram execution; the list does not
constitute authorization. `_manifest` remains available as public connector
metadata. `list_accounts` returns only the intersection of allowed account scopes
and accounts with matching grants. Worker coroutines receive immutable request
permissions and the selected account explicitly, and restore prior state afterward.

## Local editor

Open [permission-editor.html](permission-editor.html) locally in a browser. Add or
remove grants, import an existing file, review the generated JSON and save it.
The editor has no network access and does not modify a running server. Replace
the configured local file with the reviewed output, restart and reconnect clients.
Keep the file writable only by the operator; it is an authority configuration.
The editor supports JavaScript-safe integer chat IDs; larger 64-bit IDs, if
needed, must be authored directly in JSON. Server grants support signed 64-bit IDs.

Tests cover default deny, action/account/client isolation, global-policy
intersection, direct-handler fail-closed behavior, dispatcher enforcement and
parallel search context propagation. Live authenticated HTTP-client sessions
and real Telegram requests are not exercised by these tests.
