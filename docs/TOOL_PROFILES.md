# Tool Profiles

The server can advertise a small, task-focused MCP surface instead of its full
tool inventory. Set `MCP_TOOL_PROFILE` before starting the server; changing it
requires a restart so the MCP client receives a consistent `tools/list` result.

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

For a custom surface, apply exact-name `MCP_TOOL_ALLOW` and `MCP_TOOL_DENY`
filters. They run after the profile and before read-only filtering. Unknown
names stop startup, and deny always wins over allow.

Use `reader` for a first connection, `inbox` for a personal assistant, and
`community-admin` only with a narrowly scoped account/API key. The active
profile is included in both `/.well-known/mcp-server.json` and the `_manifest`
tool response, so an MCP host can explain why a tool is absent.
