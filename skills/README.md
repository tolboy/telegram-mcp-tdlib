# Companion skills

[Telegram Inbox Assistant](telegram-inbox-assistant/SKILL.md) is a free,
Apache-2.0 companion for bounded, read-only inbox triage and conversation context.
It requires the local MCP server and a separately authorized Telegram account;
see [setup](telegram-inbox-assistant/SETUP.md).

Source and configuration examples live in `telegram-inbox-assistant/`.
[Validation](VALIDATION.md) records the tested scope and client limitations.
[Agensi listing copy](AGENSI_LISTING.md) is maintained separately from installed
skill instructions. The [public listing](https://www.agensi.io/skills/telegram-inbox-assistant)
was updated on 2026-11-01 for version 0.1.2 after server 1.18.1 was published.
Agensi accepted the replacement ZIP for security scan and review; the previous
approved package stays available until approval. Listing text is already live.
See [publication evidence](../docs/RELEASE_READINESS_1.18.1.md).

Skill 0.1.3 recommends server 1.19.0 and keeps minimum compatibility at 1.17.2.
It adds scoped guidance for optional resources/subscriptions and HTTP 413.
The same eight-file skill bundle was submitted to Agensi as a ZIP and to
MCPRush as a folder on 2026-11-02. Approval and marketplace-delivered installation
remain unverified. See the shared [marketplace description](MARKETPLACE_DESCRIPTION.md),
[MCPRush listing record](MCPRUSH_LISTING.md), and [validation](VALIDATION.md).
Either delivery still requires a separately configured local Telegram server.

Build the ZIP with Python 3.11 or newer:

```sh
python scripts/package-telegram-skill.py
```

The script validates the JSON/TOML templates, includes only the explicit public
file list, checks the archive and writes a SHA-256 sidecar. The default output
is `build/skills/telegram-inbox-assistant.zip`, with `SKILL.md` at the ZIP root.
The ZIP contains neither the server runtime nor local configuration/session
files. No marketplace connection is needed to build it.
