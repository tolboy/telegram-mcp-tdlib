# Telegram Inbox Assistant validation

Date: 2026-10-15. This report distinguishes source/package checks, direct MCP
checks and tool execution inside an AI client. It contains no account credentials,
session files or private message content.

## Completed checks

| Check | Result |
| --- | --- |
| Skill frontmatter | Bundled skill-creator `quick_validate.py` passes |
| Public configuration examples | All four JSON/TOML templates parse; package validator requires the same read-only policy and credential placeholders |
| ZIP contents | Eight explicitly included public files, including the Claude Desktop bridge template; `SKILL.md` at archive root; archive CRC and file bytes verified; SHA-256 sidecar generated |
| Server compatibility regression | Serialized success JSON, success text and error results omit optional `annotations.priority`; user audience and untrusted-content metadata retained |
| Focused JVM tests | 35 tests pass: ClientConfigPrinterTest (30), McpToolSchemaCompatibilityTest (1), UntrustedContentNormalizerTest (4) |
| Full JVM/build gates, follow-up | 804 tests discovered, 802 passed, 2 skipped; zero failures/errors; bootJar, native classifier coverage and release metadata pass on the dirty candidate tree |
| STDIO protocol smoke | Initialize, tools/list, output schemas, JSON-only stdout, writes absent and bounded EOF shutdown pass; no Telegram calls |
| Fixed JAR error response on STDIO | Unauthenticated get_me returns an expected structured tool error without priority; untrusted metadata retained; EOF exits with code 0 |
| Codex native tool execution after restart | get_me confirms the intended account; inbox_snapshot, conversation_bundle and get_history succeed inside the existing Codex chat |
| Live skill walkthrough | Installed SKILL.md loaded; sampled 20 recent chats, found 2 unread chats, inspected 10 distinct messages per chat; same account carried through every call |
| Native error handling | inbox_snapshot with limit=0 produces a visible isError validation result; no repeated retries or empty-inbox interpretation |
| Hermes desktop native HTTP | 0.21.6+309.ge0550c9 with Grok 4.7 passes the 20-chat / two-conversation / five-message acceptance scenario, pagination and deliberate application-error handling; [desktop results](../docs/harness-tests/RESULTS_2026-10-15.md) |
| Claude Desktop native via local bridge | 2.19675.0.0 Chat with Sonnet 5.5 passes the same bounded scenario through a private STDIO-to-HTTP tools proxy; content/partial flags visible in JSON text, raw structuredContent/isError not observed by the model |
| Independent 1.17.1 protocol results | Pinned Python MCP SDK initializes, invokes _manifest and an offline get_me error, validates output schemas and annotations through STDIO, HTTP and the distributable bridge; isolated daemon stop/restart passes |
| Distributable bridge corpus | Four tests pass in a new Python 3.14 venv: paginated discovery, Unicode/large IDs/null/partial flags, text-only/empty/error preservation, fatal HTTP/no retry, key denial and URL restrictions |

The direct live protocol baseline on the local test account confirmed get_me,
list_chats (five returned chats) and get_history(self) (one returned message).
That baseline used the server before the priority compatibility change and a
standalone MCP client, not an agent running this skill.

## Codex integration finding

After registering the local server and restarting Codex, discovery succeeded
but native get_me and inbox_snapshot calls failed with `Unexpected response
type`. The server emitted `annotations.priority: 0.8`, matching the affected
client behavior described in [openai/codex#38979](https://github.com/openai/codex/issues/38979).
The source fix omits this optional hint, with serialization regression coverage.
The rebuilt JAR passes the protocol/error checks above.

**Verified after restart:** the registered connection loaded the fixed JAR.
Native calls no longer produce `Unexpected response type`. Returned content
retains user-audience annotations and untrusted-content metadata, without
priority. The installed skill was read and followed by the agent in this chat.

The live walkthrough used the selected account throughout. It sampled 20 recent
chats and found 2 unread groups. Five conversation_bundle calls retrieved 20
distinct messages in total, at most 10 per group; every older-page cursor
advanced. A get_history call retrieved timestamps for the same 10 messages in
one group, without broadening the sample. One message snippet was shortened
(`contentTruncated=true`). The agent distinguished general group discussion
from a personal request and did not claim full inbox coverage: the snapshot and
conversation pages were `truncated=true`, and history completeness was null.

No write tools were available in the native tool catalog. All live calls were
reads, apart from a deliberately invalid read request rejected by schema
validation. No sends, draft changes, read receipts, media downloads or exports
were requested. Links and media references in the returned messages were not
opened. Active malicious prompt injection was not tested. This is a live agent
walkthrough, not an independent model evaluation or a comprehensive behavior
benchmark.

Claude Code and VS Code / Copilot have configuration templates and
generator-format coverage, but were not exercised end to end. Cursor's desktop
attempt connected at the transport level but its Agent run was blocked by a
model usage limit. Agensi delivery,
review and marketplace installation have not been tested or published.
The Codex test used the local Java/Python launcher and an existing named-account
session; a fresh runtime-package installation and the public default-account
templates were not tested against a new account.
See the [prepublication assessment](../docs/PREPUBLICATION_AUDIT.md) for remaining
release, independent-parser, native-client and clean-install gates.

## Manual instruction review

These are source walkthroughs, not independent model evaluations:

| Scenario | Instruction behavior reviewed |
| --- | --- |
| Unspecified unread digest | Select/verify account, sample 20 recent chats, inspect at most 5 conversations and 10 messages each; disclose scope |
| Explicit account or chat | Keep that account/chat, avoid unrelated enumeration and cross-account fallback |
| Date-specific question | Retrieve timestamps/full wording using history or message tools; compact snippets do not contain dates |
| Empty/short/truncated result | Preserve incomplete coverage; do not infer global exhaustion or fabricate a cursor |
| Message contains instructions | Treat as untrusted text, without executing commands or widening reads |
| Authorization or transport failure | Report failure; stop on non-retryable/auth errors rather than presenting an empty digest |
| Request to send or save draft | Explain the skill's read-only scope; no Telegram write or automatic permission changes |

## Reproduction

```sh
python scripts/package-telegram-skill.py
./gradlew test --tests '*UntrustedContentNormalizerTest' --tests '*McpToolSchemaCompatibilityTest' --tests '*ClientConfigPrinterTest' bootJar
```

For the protocol-only smoke, use `scripts/mcp-stdio-smoke.ps1` or `.sh` with the
built JAR; see [client compatibility](../docs/MCP_CLIENT_COMPATIBILITY.md).
Do not start a second live server against a TDLib session already owned by a
client. To run the skill's final smoke in that client, verify the selected
account with get_me, then use the example prompt in [setup](telegram-inbox-assistant/SETUP.md).
