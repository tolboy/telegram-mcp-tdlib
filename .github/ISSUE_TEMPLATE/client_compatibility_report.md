---
name: Client compatibility report
about: Report how this server behaves with a specific MCP client (working or not)
title: "[Compat]: "
labels: client-compatibility
---

## Client

- Client name and version (Claude Desktop, Cursor, VS Code, Codex, other):
- OS and architecture:
- Transport (STDIO or Streamable HTTP):

## Server

- Server version / release bundle used:
- Tool profile and read-only setting (`MCP_TOOL_PROFILE`, `MCP_READ_ONLY`):

## What works / what doesn't

Describe handshake, tool listing, tool calls, confirmation flow — whatever you
exercised. "Everything works" reports are just as valuable as failures.

## Config used

Paste your MCP server entry with all credentials, phone numbers, and paths
redacted.

## Logs (if failing)

Paste relevant redacted logs. Never include API hashes, API keys, session data,
or private Telegram content.
