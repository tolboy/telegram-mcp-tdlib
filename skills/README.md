# Companion skills

[Telegram Inbox Assistant](telegram-inbox-assistant/SKILL.md) is a free,
Apache-2.0 companion for bounded, read-only inbox triage and conversation context.
It requires the local MCP server and a separately authorized Telegram account;
see [setup](telegram-inbox-assistant/SETUP.md).

Source and configuration examples live in `telegram-inbox-assistant/`.
[Validation](VALIDATION.md) records the tested scope and client limitations.
[Agensi listing copy](AGENSI_LISTING.md) is maintained separately from installed
skill instructions. The [public listing](https://www.agensi.io/skills/telegram-inbox-assistant)
was checked on 2026-11-01 and advertises version 0.1.1. The local 0.1.2 update
must be uploaded separately; rebuilding a ZIP does not update Agensi.

Build the ZIP with Python 3.11 or newer:

```sh
python scripts/package-telegram-skill.py
```

The script validates the JSON/TOML templates, includes only the explicit public
file list, checks the archive and writes a SHA-256 sidecar. The default output
is `build/skills/telegram-inbox-assistant.zip`, with `SKILL.md` at the ZIP root.
The ZIP contains neither the server runtime nor local configuration/session
files. No marketplace connection is needed to build it.
