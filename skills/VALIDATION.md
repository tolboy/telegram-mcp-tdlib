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
review and marketplace installation had not been tested or published at the
time of that 2026-10-15 walkthrough. A public 0.1.1 listing was subsequently
confirmed on 2026-11-01 (see the follow-up below); delivery remains untested.
The Codex test used the local Java/Python launcher and an existing named-account
session; a fresh runtime-package installation and the public default-account
templates were not tested against a new account.
See the [prepublication assessment](../docs/PREPUBLICATION_AUDIT.md) for remaining
release, independent-parser, native-client and clean-install gates.

## Follow-up review, 2026-11-01

The [public Agensi page](https://www.agensi.io/skills/telegram-inbox-assistant)
was inspected and advertises skill 0.1.1 with the bounded read-only scope.
Publication is therefore confirmed; acquisition through Agensi and automatic
skill loading from its delivery surface were not exercised.

Local skill **0.1.2** clarifies two source-verified result contracts:
successful text content can be the data object without the structured envelope,
whereas error text can be plain `Error: ...`; and only `conversation_bundle`
returns `nextMessageId`, while `get_history` returns a message list. The reading
budget applies across all pages. This update does not change Telegram access.

On Python 3.14.3, skill-creator `quick_validate.py` passed, the package regression
test passed, and both default and versioned archives were rebuilt with eight
public files. The test checks deterministic LF/CRLF packaging and excludes a
synthetic private credential file. The 0.1.2 archive SHA-256 is
`28b1ba24ee0f08d497001e02d12043a94fb37a388405217b9fed31ff689fe62d`.
No live client walkthrough was repeated for this instruction-only update, and
the new archive was not uploaded to Agensi during that local-review stage.

### Publication follow-up, 2026-11-01

Server 1.18.1 was published after PR #47 and the full tag workflow passed.
Downloaded JAR and Windows runtime checksums and GitHub SLSA provenance verified;
independent SDK checks passed on the published artifacts (JAR STDIO/HTTP/bridge
and restart; Windows runtime STDIO), each reporting server 1.18.1.

The canonical skill ZIP from that GitHub Release has SHA-256
`8263aa81e5f2bc4855846e133c77d490f77a1920c7b47613a55e7a28b1273edd`.
Its eight extracted files match the reviewed local 0.1.2 ZIP byte for byte.
The compressed archive digest differs across zlib implementations, as documented
in the packager; use the release artifact and its published checksum together.

That release ZIP was submitted through the existing Edge session to Agensi.
The final receipt said: "Changes saved" and that listing changes are live,
while the new file goes live after security scan and review. The UI states
review normally takes 24–48 hours. Approval and actual marketplace-delivered
package contents have not been verified. Fresh-account authorization remains
unverified. See [release evidence](../docs/RELEASE_READINESS_1.18.1.md).

### Marketplace update, 2026-11-02

Skill **0.1.3** recommends server **1.19.0** and retains minimum compatibility
with **1.17.2**. Source review against `docs/RESOURCES.md` covered the new
optional resources/subscriptions, unchanged account/read budgets, URI-only
notifications, unsubscription after monitoring, and HTTP 413 handling. The
skill remains read-only and does not enable resources or expand permissions.

Skill-creator `quick_validate.py`, the existing package regression test and
`git diff --check` passed. The versioned ZIP contains exactly eight public
files. Its SHA-256 is
`b72ca97514bc5a1962bad54c965a7081a10614797257c6e4e3730dcde23b4d75`.
This is a separate marketplace package; the already-published GitHub 1.19.0
release ZIP was not replaced.

Agensi accepted the replacement submission. Reopening its editor confirmed the
saved 0.1.3 ZIP filename and updated description. Approval of the replacement
file and delivered contents remain unverified. MCPRush accepted the same eight
files through folder upload; its final receipt and Skills table confirm **in
review**, **v0.1.3**, **Free**, with an estimate of up to two working days. See
the [Agensi record](AGENSI_LISTING.md) and [MCPRush record](MCPRUSH_LISTING.md).

No live client walkthrough, subscription monitoring, marketplace-delivered
installation or fresh-account authorization was repeated for this instruction
update. Historical client validation above is not a new 1.19.0 runtime test.

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

## Agensi 0.1.4 package, 2026-11-09

The packaging unit tests passed and the ZIP contained the eight allowlisted
public files. The submitted `telegram-inbox-assistant-0.1.4.zip` has SHA-256
`62cf518805ab4d8daff6bf511d364d4a8682184133b0ac4cdd8ebc288ea7fd15`.
Agensi accepted it for security scan and review. Marketplace delivery of
the replacement ZIP is pending.
