# Native harness acceptance prompts

Paste the complete content of the matching file into the local tool-enabled
agent chat:

- [Claude](CLAUDE.md)
- [Hermes](HERMES.md)
- [Cursor](CURSOR.md)

Each entry instructs the agent to read the same local [COMMON.md](COMMON.md),
so comparisons use the same scenarios, budget and report format. If the chat
cannot read workspace files, paste COMMON.md after its entry prompt.

## Before starting

These prompts test an already connected native MCP integration. They do not
purchase model access or silently reconfigure clients. If Claude cannot run a
model at all, no prompt can make that test execute; independent Inspector/SDK
tests remain possible, but the Claude model/skill case stays blocked. Work mode
is not a prerequisite of these prompts.

The local setup was migrated to one read-only HTTP daemon on
`http://127.0.0.1:18080/mcp`. Codex's saved configuration now uses HTTP; an older
open connection must be reloaded. Private snippets for Claude, Hermes and Cursor
are under `C:\Users\anato\AppData\Local\TelegramMcpServer\codex-baratnor\client-configs`.
They contain separate API keys and must not be copied into Git. Their README
explains where to merge each entry. During the authorized desktop test on
2026-10-15, Hermes and Cursor configurations were merged with their existing
entries, and Claude's old Telegram STDIO entry was replaced with a local
STDIO-to-HTTP test bridge. Backups remain private. Do not start the old STDIO launcher concurrently against
the same session. Configuration import and lifecycle testing are separate from
these bounded acceptance prompts. No restart, termination, logout or session
copying is authorized by the prompts themselves.

Reports belong under ignored `build/harness-reports/`; no message bodies or
private chat identifiers should enter Git. A blocked setup or model-access case
is a useful diagnosis, not a server-compatibility failure or a native pass.

## What passing means

One native pass covers only the observed harness version, transport, account
setup and bounded workflow. A shared HTTP pass does not certify that harness's
STDIO lifecycle. Loading instructions from a file does not certify automatic
skill discovery or Agensi installation.

For wider confidence, follow [the audit plan](../PREPUBLICATION_AUDIT.md):
independent protocol/result checks, a synthetic response corpus replayed through
clients, clean runtime installation, lifecycle checks and marketplace delivery.
Keep known compatible versions and limitations in the public matrix. These
tests reduce implementation-specific failures; they cannot certify every
marketplace client or future client release.
