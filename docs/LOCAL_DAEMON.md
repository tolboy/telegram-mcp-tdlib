# Managed local daemon

One daemon owns the TDLib sessions and serves multiple independent MCP HTTP
connections. Clients can disconnect without terminating that process.
Claude Desktop Chat can use the optional [tools bridge](CLAUDE_DESKTOP_BRIDGE.md)
when sharing this local daemon with HTTP-capable hosts.

```sh
telegram-mcp auth --method qr
telegram-mcp session doctor
telegram-mcp daemon start
telegram-mcp daemon status
telegram-mcp daemon attach --client cursor
telegram-mcp daemon attach --client codex
telegram-mcp daemon stop
```

Run `auth` only while the daemon and other owners of that TDLib directory are
stopped. Set Telegram credentials and desired tool policy in the environment
before `daemon start`, just as for `serve`. The daemon inherits that environment;
changing it requires stop/start. Existing standalone STDIO owners are not
automatically stopped. A session conflict fails normally and is recorded in logs.

`start --port 18080` chooses a different local port (1024–65535; default 8080).
Repeated start attaches to the already managed process and reports its actual
endpoint. The process binds only to `127.0.0.1`, forces HTTP/API-key mode and the
`daemon` Spring profile, and holds an OS lock for its entire lifetime. On Windows
it uses the bundled/runtime Java's `javaw.exe`, without a visible console window.
From version 1.17.2 it explicitly requests Windows Job Object breakaway so closing
the app that launched it does not kill the shared daemon. This preserves the same
user identity and environment; it does not elevate privileges. If the host's job
forbids breakaway, startup fails with an actionable error rather than reporting
a short-lived attached process as ready. Start from an independent terminal in
that case. No service or automatic startup is installed.

`READY` verifies an authenticated HTTP control endpoint bound to that particular
instance. It means the MCP server is ready, not that Telegram login is complete.
Startup waits up to 45 seconds and may report `STARTING`; inspect status again.
`STARTING_OR_UNHEALTHY` means the recorded process exists but readiness could not
be verified. Stale PID records are distinguished from live processes by their
start instant. Stop uses an authenticated, direct-loopback, instance-bound
request and the existing bounded graceful shutdown path. It refuses to signal an
unverified process; inspect the log if startup is stuck. No automatic restart or
OS login/startup service is installed.

`attach --client claude-code|cursor|vscode|codex` prints configuration containing
the generated local API key. Save it in that client's configuration; treat it as
a credential. All these default entries share identity `mcp-client` and the
daemon's tool/permission policy. For different client grants use the existing
named/scoped HTTP keys and `config --http <url>` workflow instead. Claude Desktop
remote connectors require network-reachable HTTPS/OAuth and are deliberately
not supported by this local API-key attachment command.

The runtime creates an owner-only `daemon/` directory below the application data
directory (`TELEGRAM_MCP_DATA_DIR` overrides it), containing `api-key`,
`state.json`, two lock files and `daemon.log`. The key persists across restarts.
The server is a child JVM of the same installation: keep that runtime/JAR in place
while it runs. Stop before upgrading, rotating the key, moving data or doing
session maintenance. Logs append across starts; rotate them with the daemon stopped.

## Telegram authorization and MCP sessions

A shared daemon has one listener and many independent MCP connections. These
connections do not share or transfer Telegram login material:

- TDLib owns the persisted Telegram authorization inside the daemon. It opens
  one isolated database/client per configured Telegram account, regardless of
  the number of MCP clients.
- Each HTTP request authenticates with its API key (or configured OAuth mode).
  Named keys may have different identities and allowed accounts. Per-client
  permissions are checked for the current request; a session ID is not a key.
- Each MCP initialization establishes a connection session and returns its own
  `Mcp-Session-Id`. The client sends that header on subsequent requests together
  with authentication, and includes the chosen `account` tool argument.

Thus several harnesses can read the same Telegram account through one port;
they observe the same Telegram state and share its account-level limits. Their
MCP session IDs are distinct. A daemon restart requires clients to initialize
again, while TDLib reuses its persisted authorization unless Telegram has
revoked it. Login codes, API hashes and the session database are not passed to
the harnesses.

The standard `daemon attach` command uses the default shared `mcp-client` key.
To distinguish harnesses, configure `mcp.security.clients` with a separate key
file, identity and allowed account list for each, and use
`MCP_PERMISSIONS_FILE` for read-only grants. Generate client configuration with
the corresponding named key, not the default attachment key. Credentials belong
in private local configuration, not distributable skill templates.

## Reproducible transport check

Build with `gradlew test bootJar`, then run in PowerShell 7:

```powershell
./scripts/Test-ManagedDaemon.ps1 -Jar /absolute/path/to/telegram-mcp-server.jar
```

The script uses an isolated temporary directory and strips Telegram/server
configuration from its child environment. It checks duplicate start, two distinct
MCP sessions, tool discovery, rejected unauthenticated control, stop and restart.
It restores the calling environment and stops its daemon in `finally`. Logs and
state are retained at the printed evidence path. It performs no Telegram call.
Actual login, message retrieval and integration inside named client applications
require separate live verification.
