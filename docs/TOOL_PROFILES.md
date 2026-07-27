# Tool Profiles

The server can advertise a small, task-focused MCP surface instead of its full
tool inventory. Set `MCP_TOOL_PROFILE` before starting the server; changing it
requires a restart so the MCP client receives a consistent `tools/list` result.
The default is `reader`; selecting `all` is always explicit.

| Profile | Intended use | Write tools advertised? |
|---|---|---|
| `all` | Full Telegram administration and automation | Yes, subject to normal server policy |
| `reader` | Safe account exploration and analysis | Never |
| `inbox` | Personal messages, drafts, media, contacts, and privacy | Yes, when `MCP_READ_ONLY=false` |
| `community-admin` | Group/channel moderation, permissions, topics, and bot commands | Yes, when `MCP_READ_ONLY=false` |
| `research` | Evidence-based account/public-chat discovery | Never |

Profiles compose with the safety model. `MCP_READ_ONLY=true` always removes
write and quota-consuming operations, even from `inbox` and `community-admin`.
Chat allow-lists, account scopes, confirmation requirements, audit logging,
and anti-spam checks still run when an advertised tool is invoked.

`MCP_CONFIRMATION_REQUIRED=true` is a server-side caller-acknowledgement gate:
destructive calls must include `"confirmed": true`. It does not prove that a
human supplied that value — the flag is an argument of the call, so the model
can set it on its own, including when a message it just read told it to.

`MCP_DESTRUCTIVE_APPROVAL=elicitation` obtains the answer from a person
instead. The server asks the MCP host before running a destructive tool and
waits; the reply travels over the protocol, not through the model's turn. A
client that has not advertised the elicitation capability cannot run those
tools at all, because a silent fall back to the flag would promise an approval
the session cannot deliver.

For a custom surface, apply exact-name `MCP_TOOL_ALLOW` and `MCP_TOOL_DENY`
filters. They run after the profile and before read-only filtering. Unknown
names stop startup, and deny always wins over allow.

Use `reader` for a first connection, `inbox` for a personal assistant, and
`community-admin` only with a narrowly scoped account/API key. The active
profile is included in both `/.well-known/mcp-server.json` and the `_manifest`
tool response, so an MCP host can explain why a tool is absent.
