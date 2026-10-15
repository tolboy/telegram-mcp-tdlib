# Agensi listing draft

**Title:** Telegram Inbox Assistant

**Price:** Free

**Summary:** Understand unread Telegram conversations, spot requests awaiting
your reply, and retrieve context without sending messages or marking them read.

**Description:** A companion skill for the open-source, TDLib-powered
telegram-mcp-tdlib server. It guides your agent through account verification,
bounded inbox reading and evidence-based summaries. It reports incomplete
coverage and treats Telegram messages as untrusted data. Suggested replies
remain text in the AI conversation; the skill never saves or sends them.

**Prerequisites:** A separately installed local telegram-mcp-tdlib server, your
own Telegram account authorized locally, Telegram application credentials, server version 1.17.2 or later,
and an AI client supporting both MCP tools and skill instructions. The server
must run with `MCP_TOOL_PROFILE=inbox`, `MCP_READ_ONLY=true`, and downloads off.
Affected Codex clients require a server build containing the content-priority
compatibility fix described in the setup guide.
Agensi does not host the server. Installing the skill alone does not connect
Telegram. Credentials and session files are not included.

**Client support:** Bounded native read-only workflows were verified in Codex,
Hermes Desktop (direct HTTP) and Claude Desktop Chat (optional tools bridge),
using an existing locally authorized account. The bridge requires Python 3.11+
and its pinned Python MCP SDK in addition to the Java server runtime.
Templates for Claude Code, Cursor and VS Code / Copilot are also included;
these are examples, not end-to-end compatibility claims. Cursor is excluded
from the required native release gate. Marketplace delivery and fresh account
authorization have not been verified. Details and
the Codex priority-annotation compatibility fix are recorded in the repository's
skills/VALIDATION.md.

**Privacy:** Telegram authorization stays local. Selected Telegram profile and chat data is passed
to the user's chosen AI client/model when its tools are called.

**Source and setup:**
- https://github.com/tolboy/telegram-mcp-tdlib/tree/master/skills/telegram-inbox-assistant
- https://github.com/tolboy/telegram-mcp-tdlib/blob/master/skills/telegram-inbox-assistant/SETUP.md

**Source license:** Apache-2.0. This free skill is also distributed from GitHub;
the marketplace's own delivery terms apply separately.

**Submission:** Upload only the generated `telegram-inbox-assistant.zip`.
Its root contains `SKILL.md`, `SETUP.md`, `LICENSE`, and `templates/`.
The listing text and validation report live outside the installable skill.
