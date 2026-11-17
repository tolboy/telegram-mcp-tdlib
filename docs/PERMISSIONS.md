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

`tools/list` omits tools without a matching grant for an accessible account.
A chat-scoped grant does not reveal an account-wide tool. The startup catalog
stays global, and every call is still checked. The SDK filters each list before
serialization, including SSE responses; STDIO uses its `stdio` identity.
HTTP authentication is captured by the transport and restored on SDK workers,
so named-key/OAuth account scopes apply during listing and execution.

Account labels are exact normalized labels; `*` matches all configured accounts.
Client wildcards are not supported. Rules for the same client/account/action
are additive. Existing API-key/OAuth account restrictions and the global chat
allow-list still intersect with grants. `chat_ids` must be explicit: `null` means
unrestricted chats and permits account-wide tools; `[]` grants no chat access.
The server-wide `TELEGRAM_BLOCKED_CHAT_IDS` list takes precedence over every
grant, the static read and mutation allow-lists, and sensitive-chat opt-ins.
After a restart with an updated list, existing journal cursors and export
jobs use the active policy when accessed. Cursors advance past filtered
entries. Use the chat history to recover older entries if a chat becomes
available again.
Sensitive chats (`777000`, `489000`, `@BotFather`, `@SpamBot`) stay blocked
under an ordinary `chat_ids: null` grant. A grant can reach one by naming its
chat ID in `chat_ids`. Set the optional `"allow_sensitive_chats": true` on an
unrestricted grant to reach all sensitive chats in that account and action.
Matching grants are additive, so a grant with explicit IDs opts in only those IDs.
The flag defaults to `false`. See the README's security section.

```json
{"client":"ops","account":"work","actions":["read"],"chat_ids":null,"allow_sensitive_chats":true}
```

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
profiles, tool-name allow/deny filters, confirmation, human approval, file security
and anti-spam checks continue to apply. For read-only downloads, also enable
`MCP_READ_ONLY_ALLOW_DOWNLOADS=true`. New write tools inherit the mutate action;
new tools are denied for chat-scoped grants until reviewed.

The MCP startup registry remains the shared configured surface. Permissions are checked
on every invocation before approval and Telegram execution; the list does not
constitute authorization. `_manifest` remains available as public connector
metadata; its tool entries use the same grant/account filtering. `list_accounts` returns only the intersection of allowed account scopes
and accounts with matching grants. Worker coroutines receive immutable request
permissions and the selected account explicitly, and restore prior state afterward.

## Local editor

Open [permission-editor.html](permission-editor.html) locally in a browser. Add or
remove grants, import an existing file, review the generated JSON and save it.
The editor has no network access and does not modify a running server. Each
all-chats grant has an "Include sensitive chats" checkbox for `allow_sensitive_chats`.
It is off by default and omitted from the JSON unless checked. For a chat-scoped
grant, list the sensitive chat IDs directly. Replace
the configured local file with the reviewed output, restart and reconnect clients.
Keep the file writable only by the operator; it is an authority configuration.
The editor supports JavaScript-safe integer chat IDs; larger 64-bit IDs, if
needed, must be authored directly in JSON. Server grants support signed 64-bit IDs.

Tests cover default deny, action/account/client isolation, global-policy
intersection, direct-handler fail-closed behavior, dispatcher enforcement and
parallel search context propagation. The HTTP transport regression uses real
authenticated requests and SDK worker dispatch, with isolated offline Telegram
accounts. It does not contact Telegram.
