# Local shared-daemon validation

The authorized local test setup was migrated from the Codex-owned STDIO child
to the existing managed daemon implementation. No source/runtime feature was
added for this migration, and the Telegram session was not copied or logged out.

## Configuration

- One listener at `127.0.0.1:18080`, MCP endpoint `/mcp`; no wildcard/LAN bind.
- Existing local TDLib session for the already verified test account.
- `inbox` profile, `MCP_READ_ONLY=true`, downloads disabled.
- Four separate named client key files and read-only permissions for the
  selected account. The management identity has no Telegram read grant.
- Codex's saved MCP entry changed to Streamable HTTP. Private connection
  snippets were prepared for Claude, Hermes and Cursor. Later authorized desktop
  testing activated Hermes/Cursor entries and a local Claude STDIO-to-HTTP bridge;
  see [desktop results](harness-tests/RESULTS_2026-10-15.md).
- Existing daemon start/status/stop commands, with a local management wrapper;
  no automatic OS startup service installed.

The prior Windows STDIO process had no HTTP control endpoint. A one-time local
JDK Attach helper, scoped to its verified PID/command, invoked the application's
existing bounded ServerShutdown controller. The session doctor confirmed the
database lock was released before the daemon started. No forced process kill,
session deletion or database copying was used for this migration.

## Executed checks

| Check | Observed result |
| --- | --- |
| Daemon readiness | Authenticated instance-bound control reports READY; TDLib reports ready without reauthorization |
| Listener | TCP inspection shows 127.0.0.1 only |
| Concurrent MCP connections | Four parallel standard HTTP MCP clients initialize with four named keys; four unique session IDs |
| Telegram identity | Server get_me succeeds and matches the intended account for all four HTTP clients |
| Read-only surface | Each connection lists 35 tools, all with readOnlyHint=true |
| Missing key with valid session | Rejected with 401/403; MCP session ID does not authenticate the request |
| Invalid key | Rejected with 401/403 |
| Permission enforcement | Management key initializes but is denied account profile reads because it has no read grant |

The four concurrent clients in this section were a standard-library Python HTTP
test, not the actual Claude/Hermes/Cursor applications. Their results do not
establish native harness compatibility; subsequent desktop results are recorded
separately. The original Codex connection remained attached to the stopped
STDIO process (`Transport closed`) until MCP/app reload; its HTTP configuration
was saved, but native HTTP success in that chat was not claimed in this run.

Private scripts, keys, client config snippets, backup and anonymized check
results remain in local application data outside the repository. No private
credentials or message content are included here. All Telegram operations in
this protocol validation were read-only get_me requests. Subsequent desktop
acceptance runs also performed bounded inbox/history reads.
