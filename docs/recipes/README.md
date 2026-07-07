# Recipes

Copy-paste configurations and prompts for common tasks. Every recipe states the
smallest tool profile that can do the job and whether write access is needed —
start read-only and widen only when a recipe requires it.

| Recipe | Profile | Write access |
|---|---|---|
| [Summarize your inbox](summarize-inbox.md) | `inbox` | not needed |
| [Find a lost message](find-lost-message.md) | `reader` | not needed |
| [Research public groups and channels](research-public-groups.md) | `research` | not needed |
| [Read-only community health check](community-review-readonly.md) | `community-admin` | not needed |
| [Draft replies without sending](safe-draft-reply.md) | `inbox` | drafts only (opt-in) |

All recipes assume you completed the [Safe first run](../../README.md#safe-first-run)
(install + `telegram-mcp auth --method qr`) and connected a client via
[CLI and STDIO](../CLI_AND_STDIO.md).

Conventions used in the recipes:

- Environment variables go in the `env` block of your client's MCP server entry
  (Claude Desktop `claude_desktop_config.json`, Cursor `~/.cursor/mcp.json`,
  VS Code `.vscode/mcp.json`).
- `MCP_READ_ONLY=true` plus a narrow `MCP_TOOL_PROFILE` means mutating tools are
  **absent from the tool list**, so the model cannot attempt them at all.
- Prompts are written to be explicit about intent; adapt names and counts freely.
