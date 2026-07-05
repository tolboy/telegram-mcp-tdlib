# Public Benchmark And Implementation Roadmap

Reviewed on 2026-06-28. The repositories below are the four most-starred
standalone Telegram MCP servers returned by a GitHub repository search for
`telegram-mcp`; star counts change over time. They are implementation
references, not dependencies or upstreams.

| Repository | Stars at review | Useful strengths | Decision for this server |
|---|---:|---|---|
| [chigwell/telegram-mcp](https://github.com/chigwell/telegram-mcp) | 1,244 | Modular tool domains, read-only annotations, QR login, configurable device identity, proxy configuration, message-link creation, MCP Roots-aware file controls | Adopt the interoperable hints and onboarding ergonomics; do **not** copy its implicit read fan-out across accounts. |
| [chaindead/telegram-mcp](https://github.com/chaindead/telegram-mcp) | 335 | First-class CLI auth, installable release artifacts for major OS/architectures, client JSON-Schema compatibility guidance | Keep the tested client-compatibility matrix and checked native bundles; revisit Intel macOS only after TDLight publishes a classifier. |
| [dryeab/mcp-telegram](https://github.com/dryeab/mcp-telegram) | 246 | Clear session lifecycle (`login`, `logout`, `clear-session`) and concise tool-oriented CLI documentation | Keep the offline session doctor/clear commands and document the existing TDLib QR/phone HTTP flow. |
| [sparfenyuk/mcp-telegram](https://github.com/sparfenyuk/mcp-telegram) | 176 | Deliberately read-only baseline and practical MCP Inspector instructions | Keep read-only as the default and make it hide, rather than merely block, write/quota-consuming tools. Add Inspector smoke instructions. |
| [beautyfree/mcp-telegram](https://github.com/beautyfree/mcp-telegram) | 23 | 102-tool GramJS surface, `npx` distribution, STDIO, browser sign-in, stories, and raw MTProto escape hatch | Match its low-friction install/login path while retaining typed granular tools, TDLib, account scoping and operation policy. Do not add an unrestricted raw-API tool. |

## 2026-06-28 Follow-up

- Added standard MCP `readOnlyHint`, `destructiveHint`, `idempotentHint`, and
  `openWorldHint` annotations from the same centralized operation policy used
  for runtime enforcement.
- Added `get_message_link`, complementing `message_from_link` with TDLib-native
  public/private link generation for messages and media albums.
- Documented the existing QR/phone interactive login flow and made the Telegram
  device model and system language configurable.
- Tightened interactive-auth trust: only loopback is keyless; Docker bridges,
  reverse proxies, and private LAN peers need an MCP API key.
- Pinned every third-party GitHub Action to an immutable commit. The prior
  dependency-review `@v5` reference did not exist; it now points to the
  published `v5.0.0` commit.

## 2026-06-30 Follow-up

- Added STDIO beside Streamable HTTP with JSON-only stdout and a protocol smoke.
- Added runtime-inclusive `jpackage` app images, direct Homebrew/Scoop release
  manifests, and an OCI STDIO image for the official MCP Registry.
- Added an explicit nonce-protected browser auth CLI instead of exposing login
  as a model-callable tool.
- Added structured MCP output, user-audience annotations and reversible
  presentation-control escaping for untrusted Telegram content.
- Added exact-name surface filters and optional external OAuth resource-server
  mode with RFC 9728 discovery and account claims.

## 2026-07-02 Review — Feature Gap Adoption

Re-checked the same repositories before opening this repository to the public.
The remaining tool-surface gaps, and the decision for each:

| Feature seen in competitors | Source | Decision |
|---|---|---|
| Vote on a poll (`vote_poll`) | beautyfree | **Adopt** — `TdApi.SetPollAnswer`; write tool. |
| Close a poll (`close_poll`) | beautyfree | **Adopt** — `TdApi.StopPoll`; destructive (irreversible), confirmation-gated. |
| Edit chat description (`set_chat_description`) | beautyfree `edit_about` | **Adopt** — complements `edit_chat_title`; write tool. |
| Slow mode (`set_slow_mode`) | beautyfree | **Adopt** — `TdApi.SetChatSlowModeDelay`; confirmation-gated like other group-impacting writes. |
| Invite-link lifecycle (`list_invite_links`, `revoke_invite_link`) | beautyfree | **Adopt** — completes the existing `get_invite_link`; revoke is destructive. |
| Read receipts (`get_message_viewers`) | chigwell | **Adopt** — `TdApi.GetMessageViewers`; read-only, works in small groups. |
| Groups in common (`get_common_chats`) | chigwell | **Adopt** — `TdApi.GetGroupsInCommon`; read-only. |
| Folder reordering (`reorder_chat_folders`) | beautyfree, chigwell | **Adopt** — completes the folder tool family. |
| Stories suite (post/view/analytics) | beautyfree | **Defer** — large TDLib surface with its own privacy semantics; revisit on demand. |
| Raw MTProto escape hatch (`invoke_mtproto`) | beautyfree | **Decline** — bypasses every guardrail; explicit non-goal. |
| Ownership transfer / channel deletion | beautyfree | **Decline** — account-losing destructive actions do not belong in a model-callable surface. |
| Contact import/export | chigwell | **Decline for now** — bulk PII export conflicts with the local-first privacy posture. |
| Premium boosts | beautyfree | **Defer** — niche; no demand signal yet. |

## 2026-07-02 Review — Spring AI Reference Project (mcpulsor)

Reviewed a Spring AI + MCP demo project (two modules: annotation-driven MCP
server plus an Ollama-backed MCP host with sampling and logging handlers) for
architectural ideas beyond the Telegram-server competitor set:

| Approach seen | Decision |
|---|---|
| MCP Inspector as a compose service next to the server | **Adopted** — `docker compose --profile inspector up` starts the official Inspector wired to this server; see `MCP_CLIENT_COMPATIBILITY.md`. |
| Docs listing tools by hand with no guarantee against drift | **Adopted the inverse** — `ReadmeToolInventorySyncTest` fails the build when the README tool tables and the registered tool inventory diverge in either direction. |
| Server-side sampling (`requestContext.sample`) so a tool delegates LLM work to the client | **Defer** — attractive for summarize/draft-style tools without server-side model keys, but client support is uneven and Telegram content in sampling prompts widens the injection surface. Revisit per-tool, opt-in, with untrusted-content wrapping. |
| `@McpLogging` notifications from server to client | **Defer** — could surface anti-spam/audit blocks to the MCP client; low cost, but needs a policy for what operational detail may leave the server. |
| `@McpTool`/`@Tool` annotation-scanned registration | **Decline** — schema inferred from method signatures suits demos; this server keeps explicit JSON Schemas tied to guard, profile, and annotation policy tables. |
| Hand-rolled `<tool_call>` prompt protocol for models without native tool support | **Not applicable** — host-side concern; this repository is a connector, not a host. |

## Current Differentiators To Preserve

- TDLib user-account support, instead of a Bot API-only integration.
- Mandatory account selection in multi-account mode, account-scoped API keys,
  isolated TDLib directories, audit trails, and anti-spam limits.
- No implicit cross-account reads. A user must opt into a specific account;
  account labels never disclose a phone number or profile name.
- Platform-aware state paths and secret-file validation.

## Implemented In v1.2.0

`transcribe_voice_note` invokes TDLib `recognizeSpeech` for one existing voice
message. Telegram attaches the transcript to that same message; the server
returns the completed text or a `PENDING` state, without uploading audio to a
third-party speech provider or sending a new message. Telegram may require a
Premium subscription or an available trial allowance. The tool is protected by
read-only mode, the normal chat allow-list, account scoping, audit logging, and
a six-per-minute / sixty-per-day anti-spam policy.

## Prioritized Follow-up Plan

| Priority | Change | Benefit | Cost | Status / why it fits without a rewrite |
|---|---|---|---|---|
| P1 | **Tool-surface read-only policy**: register only non-mutating tools when `MCP_READ_ONLY=true`, rather than exposing all tools and rejecting writes at invocation time | High | Small | Implemented. `OperationGuardService` remains the shared classifier and invocation-time defense; `McpConfig` now filters the advertised surface. |
| P1 | **MCP client compatibility matrix**: Inspector, Claude Desktop, Cursor, VS Code and Streamable HTTP smoke checks; document any JSON-Schema constraints | High | Small | Implemented as a reproducible Streamable HTTP smoke, a conservative schema contract test, and client-facing connection matrix. Client UI acceptance remains a version-specific manual check. |
| P1 | **Telegram proxy configuration**: SOCKS5, HTTP CONNECT and MTProxy with per-account overrides and `*_FILE` password/secret support | High for restricted networks | Medium | Implemented with isolated TDLib account startup. Invalid, partial, or mismatched proxy credentials fail before Telegram networking; each named account configures its own proxy. |
| P2 | **Session doctor and maintenance CLI**: report OS data directory, registered account labels, TDLib lock ownership, and safe logout/clear instructions without printing secrets | Medium | Small | Implemented as an offline `session doctor/logout/clear` JAR command. It reports configured labels and portable lock availability without starting TDLib; clearing is explicit, lock-gated, and limited to standard per-account storage. |
| P2 | **Folders and scheduled messages** | Medium | Medium | Implemented as focused folder and schedule tools. Folder listing uses TDLib's account update stream (the API has no synchronous folder-list function); mutations stay account-scoped, allow-list checked, audited, and read-only filtered. |
| P3 | **Privacy settings, bot commands, and full group-permission controls** | Medium | Large | Implemented as focused tools: complete per-setting privacy rule replacement, scoped bot command menus, default/member permissions, and all TDLib administrator-right flags. Writes retain account scoping, audit, allow-list checks for referenced chats, read-only hiding, and confirmation protection for group-impacting changes. |
| P3 | **Native installers/binaries for every architecture** | Medium | Large | Implemented for every TDLight classifier currently bundled: checked Windows x64, Linux x64/ARM64, and macOS ARM64 release archives plus SHA-256 manifest and CI JAR-content verification. Intel macOS remains explicitly blocked until TDLight publishes a matching classifier. |

## Explicit Non-Goals

- Do not fan out read tools to every configured account by default: it can
  disclose unrelated conversations to a single model turn and makes costs and
  audit intent ambiguous.
- Do not store Telegram sessions as portable environment strings by default:
  encrypted OS/volume-backed TDLib state plus scoped secret mounts better fits
  the server's local-first security model.
- Do not add an external speech-to-text provider for native Telegram voice
  transcription. It would change the privacy boundary and duplicate Telegram's
  Premium feature.
