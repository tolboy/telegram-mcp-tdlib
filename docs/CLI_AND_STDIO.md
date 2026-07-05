# CLI And STDIO

The release launcher and runnable JAR expose one stable command line:

```text
telegram-mcp serve [--transport streamable-http|stdio] [Spring options]
telegram-mcp auth [--account <label>] [--method qr|phone] [--no-browser]
telegram-mcp session <doctor|logout|clear> [options]
telegram-mcp version
```

Running the JAR without a command remains backwards-compatible and starts
Streamable HTTP. `MCP_TRANSPORT` selects a transport when no explicit
`--transport` is present; the CLI flag takes precedence.

## STDIO

STDIO is intended for a local MCP client that starts the server as a child
process. JSON-RPC is the only data written to stdout; application logs go to
stderr.

```json
{
  "mcpServers": {
    "telegram": {
      "command": "telegram-mcp",
      "args": ["serve", "--transport", "stdio"],
      "env": {
        "TDLIB_API_ID": "123456",
        "TDLIB_API_HASH_FILE": "/absolute/path/to/api-hash",
        "MCP_TOOL_PROFILE": "reader",
        "MCP_READ_ONLY": "true"
      }
    }
  }
}
```

Prepare the TDLib session before enabling the MCP entry:

```bash
telegram-mcp auth --account default --method qr
```

The wizard binds a random IPv4 loopback port, protects every auth request with
a one-time nonce, renders QR data locally, and terminates after TDLib persists
the session. `--no-browser` runs the same QR/phone/code/2FA state machine in
the terminal; API hashes, login codes, and passwords are read from stdin
rather than command-line arguments.

Named-account authentication uses the account's configured storage/session
settings when present, and otherwise uses the standard per-account application
data directory.

## Surface policy

Filtering order is deterministic:

1. `MCP_TOOL_PROFILE`
2. `MCP_TOOL_ALLOW`
3. `MCP_TOOL_DENY`
4. `MCP_READ_ONLY`

Allow/deny values are exact tool names. Wildcards are intentionally unsupported,
and an unknown name fails startup rather than silently weakening policy.

Run the no-Telegram protocol smoke against a JAR:

```powershell
./scripts/mcp-stdio-smoke.ps1 -Jar build/libs/telegram-mcp-server.jar `
  -RequiredTool get_history -ForbiddenTool send_message
```
