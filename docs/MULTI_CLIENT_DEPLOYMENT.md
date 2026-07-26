# Multi-client Deployment

Use one long-lived Streamable HTTP server when two or more MCP clients need the
same Telegram account or `TDLIB_DATA_DIR`. Do not configure each client to
spawn its own STDIO process against that shared directory.

TDLib grants one process exclusive ownership of its session database. Two
STDIO children therefore do not form a pool: the first owns `td.binlog`, and
the second fails fast with exit code 2. Retrying or deleting the lock file does
not make concurrent access safe.

| Use case | Recommended transport |
|---|---|
| One local MCP client owns the process | STDIO |
| Claude, Cursor, VS Code, Codex, or other clients share one Telegram session | One Streamable HTTP daemon; every client uses its `/mcp` endpoint |
| Processes intentionally use different accounts and different TDLib directories | Separate STDIO processes are valid |

## Local shared daemon

Authenticate the account once, then start a single server:

```bash
telegram-mcp auth --account default --method qr

export SERVER_ADDRESS=127.0.0.1
export MCP_API_KEY_FILE=/absolute/path/to/mcp-api-key
export MCP_TOOL_PROFILE=reader
export MCP_READ_ONLY=true
telegram-mcp serve --transport streamable-http
```

Point every client at `http://127.0.0.1:8080/mcp` and provide an authorized
`Authorization: Bearer <key>` header through that client's secret mechanism.
Clients may use one shared local key or distinct named/scoped keys. Each client
gets its own MCP session ID, while the one server process remains the sole
owner of the TDLib database.

For Docker Compose, the equivalent long-lived process is:

```bash
docker compose up -d telegram-mcp
```

The published compose stack exposes the endpoint on loopback. Keep
`MCP_READ_ONLY=true` until writes are intentional, and prefer named/scoped API
keys when different clients need different account access. A non-loopback bind
must have API-key or OAuth protection.

## Operations

- Stop the daemon gracefully before session maintenance or re-authentication.
- If a new process reports a locked `td.binlog`, find and stop the existing
  owner. Do not delete `td.binlog` while that process is alive.
- Do not point a temporary smoke test at a production session directory. The
  protocol smoke scripts intentionally use invalid Telegram credentials and
  make no Telegram tool call.
- A separate `TDLIB_DATA_DIR` creates an independent TDLib session; it is not a
  second connection to the same on-disk session.

See [CLI and STDIO](CLI_AND_STDIO.md) for child-process lifecycle details and
[MCP client compatibility](MCP_CLIENT_COMPATIBILITY.md) for connection checks.
