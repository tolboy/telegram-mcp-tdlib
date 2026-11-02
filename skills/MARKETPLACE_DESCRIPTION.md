A free companion skill for the open-source, TDLib-powered telegram-mcp-tdlib server.

## What it does
- Summarizes unread conversations and identifies explicit requests awaiting your reply.
- Retrieves context from a selected chat and highlights deadlines supported by message timestamps.
- Verifies the selected account, keeps a bounded reading budget, and reports incomplete or truncated coverage.
- Treats Telegram messages as untrusted data. Suggested replies stay as text in the AI conversation.

By default, it samples at most 20 recent chats and inspects at most 5 relevant conversations, up to 10 messages each. This is a sampled digest, not a claim to process your entire inbox.

## Updated for server 1.19.0
Skill 0.1.3 recommends server 1.19.0 and keeps compatibility with server 1.17.2 or later. It adds guidance for optional read-only MCP resources and explicitly requested subscription monitoring. Resources remain off by default and are not required for inbox triage. Reads and re-reads keep the same account and budget; monitoring ends with unsubscription. HTTP 413 means reduce an oversized request, not widen server limits.

The server's new media and moderation tools do not expand this skill's read-only scope.

## Setup required
Install telegram-mcp-tdlib separately, then authorize your own Telegram account locally using your Telegram application credentials. Run the server with MCP_TOOL_PROFILE=inbox, MCP_READ_ONLY=true, and downloads disabled. Use an AI client that supports both MCP tools and skill instructions. Installing this skill alone does not connect Telegram.

The ZIP includes SKILL.md, SETUP.md, LICENSE, and client configuration templates. Credentials and Telegram session files are not included. The optional Claude Desktop tools bridge requires Python 3.11+ and the pinned MCP SDK described in SETUP.md. On a platform that delivers only SKILL.md, follow the public setup link inside it.

## Read-only scope and privacy
The skill does not send messages, save drafts, mark messages read, react, edit, delete, join chats, download media, or export data. The server must enforce read-only access. Telegram credentials and session files remain local. Selected Telegram profile and chat data is passed to your chosen AI client/model when tools or resources are read.

## Compatibility and limits
Bounded native workflows were tested in Codex, Hermes Desktop over HTTP, and Claude Desktop Chat through the optional local tools bridge, with an existing authorized account. Claude Code, Cursor and VS Code/Copilot templates are examples, not end-to-end compatibility guarantees. Marketplace-delivered installation and fresh-account authorization have not been independently verified.

## Source and documentation
Source and skill: https://github.com/tolboy/telegram-mcp-tdlib/tree/master/skills/telegram-inbox-assistant
Setup: https://github.com/tolboy/telegram-mcp-tdlib/blob/master/skills/telegram-inbox-assistant/SETUP.md
Validation: https://github.com/tolboy/telegram-mcp-tdlib/blob/master/skills/VALIDATION.md

Skill version 0.1.3. Recommended server: 1.19.0; minimum compatible server: 1.17.2. Source license: Apache-2.0. Also available free on GitHub; marketplace delivery terms apply separately.
