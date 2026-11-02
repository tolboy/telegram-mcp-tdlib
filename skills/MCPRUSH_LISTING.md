# MCPRush listing record

**Skill:** [Telegram Inbox Assistant](https://mcprush.com/tolboy/telegram-inbox-assistant)

**Version:** 0.1.3. Recommended server 1.19.0; minimum server 1.17.2.

**Price:** Free. Free Listing was selected; no paid listing upgrade was purchased.

**Category:** Communication & Collaboration. **Type:** Workflow.
**License:** Apache-2.0.

**Summary:** Read-only Telegram inbox summaries, messages awaiting a reply,
and conversation context.

The description is maintained in [MARKETPLACE_DESCRIPTION.md](MARKETPLACE_DESCRIPTION.md).
The required server selected in the catalogue is **Telegram MCP Server (TDLib)**.

## Submission, 2026-11-02

Uploaded the same eight public files as the Agensi 0.1.3 ZIP, using MCPRush's
folder upload. The UI accepted SKILL.md, SETUP.md, LICENSE and all five client
templates, including the TOML template. Every required browser-side check passed.
The optional separate `triggers` frontmatter field was not added: the existing
description declares when to use the skill and remains valid for Codex.

The final receipt stated **Your agent skill is under review**. The Skills table
then showed **Telegram Inbox Assistant v0.1.3**, **in review**, **Free**. The UI
estimates up to two working days and says the public page opens after approval.
Submission does not verify approval, marketplace delivery or fresh-account setup.

## Server release, 2026-11-02

Published server **1.19.0** through the existing listing's Releases screen. The
history identifies it as stable and what new installs get. The comment was:

> 1.19.0 adds 10 media/moderation tools (126 total), opt-in MCP resources and subscriptions, stronger approval and local-file protections, and dependency updates. Resources remain off by default; with subscriptions enabled, MCP POST bodies over 1 MiB return HTTP 413. Release: https://github.com/tolboy/telegram-mcp-tdlib/releases/tag/v1.19.0

Triggered **Read the tools again** against the listing's GitHub source. The
site's release checks still had an advisory about every tool having an input
schema; this is not recorded as a passed check. No server release assets or
existing GitHub tags were replaced.
