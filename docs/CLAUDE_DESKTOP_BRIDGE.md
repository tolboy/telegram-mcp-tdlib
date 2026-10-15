# Claude Desktop with a shared local daemon

Available with the 1.17.1 release candidate and later releases. Ordinary Claude
Desktop Chat can invoke local MCP tools even though its cloud shell cannot read
Windows files. Paste skill instructions or install them through the host's
supported skill flow; a local filesystem path alone does not give the cloud
shell filesystem access. Cowork is not required for this tools integration.

For one client, the normal server STDIO configuration remains sufficient.
For several clients using the same account, start one read-only daemon and
use this optional **tools-only STDIO-to-HTTP bridge** for Claude Desktop.
It never starts TDLib, copies a session or changes Telegram authorization.
The runtime package includes the bridge in `support/`; source users find the
same files under `scripts/`.

## Install the optional bridge

The Java server runtime is bundled; the optional bridge additionally needs
Python 3.11+ and Python MCP SDK 1.30.0. From an extracted runtime bundle:

```powershell
python -m venv C:/tools/telegram-mcp-bridge
C:/tools/telegram-mcp-bridge/Scripts/python.exe -m pip install -r C:/tools/telegram-mcp/support/requirements-mcp-client.txt
```

On macOS/Linux use the venv's `bin/python` instead of `Scripts/python.exe`.
Keep the extracted support directory and venv in stable locations. Obtain the
daemon endpoint and a private per-client API-key file from its operator. The
key authenticates MCP access; it is not the Telegram API hash or session.
Apply the read-only inbox profile and account permissions on the daemon.
See the [local daemon guide](https://github.com/tolboy/telegram-mcp-tdlib/blob/master/docs/LOCAL_DAEMON.md).

Merge into Claude Desktop's local `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "telegram": {
      "command": "C:/tools/telegram-mcp-bridge/Scripts/python.exe",
      "args": [
        "C:/tools/telegram-mcp/support/mcp-http-stdio-bridge.py",
        "--url", "http://127.0.0.1:18080/mcp",
        "--api-key-file", "C:/private/claude-telegram-key.txt"
      ]
    }
  }
}
```

Use **absolute physical paths** visible to Claude. Windows Store applications
can redirect apparent AppData paths to their own LocalCache directories.
Use Settings -> Developer -> Edit config to locate the configuration belonging
to the installed Claude application. Preserve unrelated server entries.
Use File -> Exit and relaunch after changes; closing the window can leave the
old process and tool catalog running.

If initialization fails, check `telegram-mcp daemon status` first. The bridge
connects to an already running daemon and never starts one itself. Use server
1.17.2 or later on Windows when launching the daemon from an agent application:
earlier versions can inherit that application's kill-on-close Job Object and
stop when the application exits. A stopped daemon needs `daemon start`, followed
by reconnecting the bridge in Claude; Telegram authorization remains on disk.

## Behavior and limits

- The bridge advertises only tools, preserving their schemas and annotations.
  It forwards text, structuredContent, result metadata and isError. The host may
  hide raw fields from the model even when they are on the wire.
- The upstream tool catalog is captured at connection startup. Reconnect the
  bridge after changing the daemon's profile/catalog or restarting the daemon.
  There is no automatic retry of a tool call; a transport failure can leave its
  delivery unknown. Do not retry writes based only on that failure.
  The tool-call timeout defaults to 60 seconds (`--timeout-seconds 1..600`). A
  fatal HTTP failure closes this disposable bridge process so the host can
  reconnect; the daemon and Telegram authorization remain running.
- Resources, prompts, sampling, elicitation, subscriptions and progress forwarding
  are not supported. Use the read-only skill; workflows requiring those features
  need a different integration. This is not a full MCP capability proxy.
- API keys are read from a local file and not placed in command-line arguments.
  Remote endpoints require HTTPS; cleartext is restricted to loopback. Redirects
  and environment proxy settings are disabled.
- Closing the bridge closes its MCP connection, leaving the daemon and its
  Telegram login running. The daemon owns the account and enforces permissions.

## Repeatable checks without Telegram credentials

From the source checkout, using the venv interpreter above:

```text
python -m unittest discover -s scripts/tests -v
python scripts/mcp-result-smoke.py --jar /absolute/path/to/telegram-mcp-server.jar
```

The latter creates an isolated offline server, tests actual success/error calls
over STDIO, HTTP and the bridge, then stops/restarts only its own offline daemon.
It never attaches to the user's live Telegram daemon. These SDK checks complement
the recorded native Claude/Hermes runs; they do not certify every host version.
