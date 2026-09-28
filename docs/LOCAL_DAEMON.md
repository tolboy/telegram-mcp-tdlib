# Managed local daemon

One daemon owns the TDLib sessions and serves multiple independent MCP HTTP
connections. Clients can disconnect without terminating that process.

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
