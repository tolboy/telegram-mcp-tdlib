# Setup

This free skill requires a separately installed local
[Telegram MCP server](https://github.com/tolboy/telegram-mcp-tdlib), a supported
MCP client, and a Telegram account that you authorize yourself. Agensi distributes
the instructions; it does not host the server or log in to Telegram for you.
The server uses Telegram application credentials from
[my.telegram.org](https://my.telegram.org/apps). Keep credentials and TDLib
session files on your own computer, outside the skill folder.

## 1. Install and authorize the server

Use the runtime-inclusive package for your OS from the project's
[installation guide](https://github.com/tolboy/telegram-mcp-tdlib#safe-first-run).
The packaged server needs no separately installed JDK. Check the installed
version with `telegram-mcp version`.

Use **telegram-mcp-tdlib 1.17.1 or later** for this skill. This includes the
Codex result-annotation compatibility fix. During prerelease testing, use the
named 1.17.1 candidate and keep its prerelease status visible; an older published
release does not acquire this fix by installing the skill.

With other processes using the same TDLib session stopped, run:

```sh
telegram-mcp auth --account default --method qr
telegram-mcp session doctor
```

Complete the wizard locally and scan its QR code from the intended Telegram
account. Never send API hashes, QR login links, one-time codes, 2FA passwords,
or session databases to the skill author, Agensi, or an agent conversation.
The wizard saves the Telegram session, not your API hash. Store the API hash
in a private local text file and supply its absolute path below; use the same
application credentials and session directory for authorization and serving.

Already authorized? Keep the existing account/session configuration and verify
it with `session doctor` and `get_me`. A folder name is not proof of account
identity. Do not copy a live TDLib database or run two owners of one session.
For multiple clients, configure one
[shared local daemon](https://github.com/tolboy/telegram-mcp-tdlib/blob/master/docs/LOCAL_DAEMON.md)
and put the read-only policy on the daemon itself.

## 2. Connect your MCP client

Prefer the server's config generator for your installed version:

```sh
telegram-mcp config --client codex --profile inbox
```

Other generator targets are `claude-code`, `cursor`, `vscode`, and `claude`.
The generator prints a configuration entry: merge it into the client's existing
configuration without replacing unrelated servers. Update the sample API ID
and the local API-hash-file path. Set these server environment values:

```text
TDLIB_API_ID=<your application ID>
TDLIB_API_HASH_FILE=<absolute path to your private API hash file>
MCP_TOOL_PROFILE=inbox
MCP_READ_ONLY=true
MCP_READ_ONLY_ALLOW_DOWNLOADS=false
```

The [templates](templates/) are complete syntax examples with deliberately
non-working credential placeholders. They use the default account/session.
For a named existing account, preserve its `TELEGRAM_ACCOUNTS_<LABEL>_*`
configuration, including the database directory, rather than adding a second
default account. Never bundle that private configuration with this skill.

| Client | Template | Where to merge |
| --- | --- | --- |
| Codex | [codex.toml](templates/codex.toml) | `~/.codex/config.toml` |
| Claude Code | [claude-code.json](templates/claude-code.json) | project `.mcp.json` or user `~/.claude.json` |
| Claude Desktop, shared daemon | [claude-desktop-bridge.json](templates/claude-desktop-bridge.json) | local `claude_desktop_config.json`; install the optional bridge first |
| Cursor | [cursor.json](templates/cursor.json) | `~/.cursor/mcp.json` |
| VS Code / Copilot | [vscode.json](templates/vscode.json) | project `.vscode/mcp.json` |

`telegram-mcp` must be visible on the client's PATH; otherwise set `command` to
the launcher's absolute path. Use forward slashes for paths in the examples,
including on Windows (`C:/private/telegram-api-hash.txt`). The MCP client starts
the STDIO process; do not start `serve --transport stdio` in another terminal.
Reload the client's MCP connection after saving configuration.

The formats above are supported by the server's config generator. This does
not assert a live end-to-end test in every listed client. See the repository's
`skills/VALIDATION.md` for actual test coverage and current limitations.

### Shared daemon in Claude Desktop and Hermes

For Claude Desktop Chat, follow the server's
[bridge guide](https://github.com/tolboy/telegram-mcp-tdlib/blob/master/docs/CLAUDE_DESKTOP_BRIDGE.md).
The optional tools bridge is included in the runtime bundle's `support/` folder
and as a separate release asset. It requires Python 3.11+ and its pinned SDK.
It connects to the existing daemon without opening a second TDLib session.
Use absolute paths visible to Claude and File -> Exit before relaunching after
configuration changes. Paste this SKILL.md into the chat if that surface cannot
read local files; this tests instruction loading, not automatic skill discovery.

Hermes uses `mcp_servers` in the active Hermes profile's `config.yaml`:

```yaml
mcp_servers:
  telegram:
    url: http://127.0.0.1:18080/mcp
    headers:
      Authorization: "Bearer <your private Hermes MCP client key>"
```

Merge into the existing mapping, preserving other servers. Obtain a separate
read-only key from the daemon operator; do not paste it into the agent chat.
The active profile/config location can differ between installations. Restart
or refresh MCP discovery using the host's supported flow after updating it.
This HTTP entry starts no server and carries no Telegram login material.

Native bounded reads were verified in Codex, Hermes Desktop and Claude Desktop
Chat (the latter through the tools bridge). These are specific local versions,
not proof of marketplace installation or every supported client. Cursor is not
part of this release's required native verification; its template is provided
as a configuration example. See `skills/VALIDATION.md` for exact evidence.

## 3. Install the skill

For Codex, copy this directory to `~/.codex/skills/telegram-inbox-assistant`
(or your configured Codex home's `skills/` directory) and reload skill discovery
if necessary. Keep `SKILL.md`, this guide and `templates/` together. For other
agents, use their supported skill installation flow. When acquired through
Agensi, use its delivery flow; this open-source copy is also available in the
project repository under Apache-2.0.

## 4. Verify before reading an inbox

Ask the agent to call `get_me` for the selected account and report only its
username. Confirm the correct account. Verify that `send_message`,
`save_draft`, `mark_as_read` and other write tools are absent from the server's
advertised tool list. If they are present, stop and correct the server's
read-only configuration.

Then try:

> Use telegram-inbox-assistant to inspect up to 20 recent chats and summarize
> at most 5 unread conversations, reading up to 10 messages each. Identify
> anything that clearly needs my reply. Do not change anything in Telegram,
> and explain incomplete coverage.

## Troubleshooting

- **Server missing:** confirm the launcher's absolute path, reload MCP and check
  client startup logs. A skill cannot supply unavailable tools.
- **`AUTH_REQUIRED`:** stop the session owner and complete the local auth wizard.
  Do not paste login secrets into the agent conversation.
- **Locked `td.binlog`:** another process owns the session. Stop that process
  normally or connect to the existing shared daemon. Do not delete the lock or
  session database.
- **`Unexpected response type` in Codex:** affected Codex builds reject fractional
  content annotation priorities. Use a server build containing the compatibility
  fix that omits optional `annotations.priority`, then restart the MCP connection.
  See [upstream issue](https://github.com/openai/codex/issues/38979). Do not
  interpret the failure as an empty inbox or keep retrying it.
- **Empty or partial inbox:** this is a bounded recent-chat sample, subject to
  account/chat permissions. It is not proof of zero unread messages globally.

Telegram sessions stay local, but selected tool results are passed to the AI
client/model you use. Local credentials do not imply local-only model inference.
