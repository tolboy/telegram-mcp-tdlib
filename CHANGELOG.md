# Changelog

Notable changes to Telegram MCP Server are documented here. The project follows
[Semantic Versioning](https://semver.org/).

## 1.13.0 - 2026-07-28

### Added

- `telegram-mcp config` covers Claude Code and Codex, and can emit a shared HTTP
  daemon entry. The differences it handles are the ones that fail silently:
  Codex reads TOML tables rather than JSON, VS Code reads `servers` and ignores
  an `mcpServers` block, and Claude Code states the transport `type` the others
  omit. Only shapes checked against a real configuration file are generated —
  clients that read a standard `mcpServers` object take the `cursor` output
  unchanged, and that is now stated instead of guessed at.
- `--http` emits the entry for the shared-daemon topology, which is what a
  second client needs. Claude Desktop is already that case on its own: it starts
  a separate server for Cowork from the same configuration, so one STDIO entry
  there produces two processes competing for a session that admits one.

## 1.12.0 - 2026-07-28

### Added

- `MCP_DESTRUCTIVE_APPROVAL` gains `loopback` and `auto`. Elicitation is the
  tidier route but depends on the client implementing it, and the major host
  does not: Claude Desktop advertises `elicitation=null`, so the `elicitation`
  mode shipped in 1.11.0 refuses every destructive tool there. `loopback` asks
  on a page the server hosts on `127.0.0.1` — the link goes to stderr, where the
  host shows server output to its operator, and the decision arrives on a
  separate connection, so nothing passes through the model. `auto` uses
  elicitation where the client offers it and loopback otherwise; that is not a
  downgrade, since both answers come from a person over a channel the model
  cannot write to. Links are single use, nonce-protected, loopback-only, and
  expire into a refusal after `MCP_DESTRUCTIVE_APPROVAL_TIMEOUT` (120s default).
- A client that cannot answer an elicitation prompt is now reported once per
  session, rather than leaving the operator to infer it from a failed
  `ban_user` that looks like a Telegram error.

### Changed

- Releases now verify the published image before announcing it. `1.11.0` passed
  every local gate and still shipped a termination path that failed when a
  container was stopped mid-startup, because nothing ran against the artifact
  users pull, from a session directory with no existing login.
  `scripts/verify-published-image.sh` checks that contract — initialize, stdin
  close, a signal while running, and a signal during startup — and runs between
  signing the image and publishing it to the MCP Registry, so a broken image
  cannot reach the registry or a release page. Signals go through `docker stop`
  rather than by killing the `docker run` client, which only detaches.
- The STDIO smoke now also signals the server *during* startup. Its existing
  signal phase waits for `initialize` precisely so the server is ready, which is
  why it could not have caught the 1.11.0 defect.
- `platform-smoke.yml` runs on release tags instead of only on demand. The STDIO
  lifecycle contract is checked nowhere else, and leaving it manual is what let
  an untested termination path reach a release.

## 1.11.1 - 2026-07-27

### Fixed

- A termination signal that arrived while the server was still starting left the
  process with no bounded exit. Hook registration happens after startup so a
  failed startup keeps its own exit code, but startup loads TDLib's native
  libraries and is not instant, so the signal could land first — and the JVM then
  refuses a new shutdown hook. 1.11.0 threw `IllegalStateException: Shutdown in
  progress` out of `main` and left Spring's deadline-free hook to close TDLib
  alone, so `docker stop` ran out its full grace period and escalated to SIGKILL.
  That case now requests the bounded shutdown directly.

## 1.11.0 - 2026-07-26

### Security

- Keyless API-key mode is now loopback-only. Raw HTTP binds to `127.0.0.1` by
  default, and non-loopback requests to MCP or protected Actuator endpoints are
  rejected unless an API key or OAuth is configured. Requests carrying any
  recognized forwarding header cannot use the keyless local-development path.
- `TELEGRAM_ALLOWED_CHAT_IDS` is now enforced across account-wide and
  result-derived chat surfaces: inbox/global/public search, drafts, folders,
  privacy rules, username/link resolution, last-interaction reads, and
  anti-spam notifier targets. Create/join operations whose target cannot be
  pre-validated fail closed while a static chat allow-list is active, and a
  public username is freshly rechecked against its validated numeric ID before
  joining.
- Public research discovery no longer returns rejected candidate metadata, and
  `register_internal_chat` no longer exposes the complete internal-chat set.
  Contract tests inventory the non-direct chat paths so future additions fail
  CI until their allow-list behavior is explicit.

### Added

- `MCP_DESTRUCTIVE_APPROVAL=elicitation` gates destructive tools behind a human
  answer obtained through the MCP host, over a channel the model cannot write
  to. `confirmed: true` travels inside the tool call, so a prompt-injected model
  can set it unprompted; an elicited answer comes back from the person instead.
  The gate runs at the shared dispatch, ahead of the handler, so no Telegram
  call precedes the decision and a destructive tool added later inherits it.
  Clients that do not advertise the elicitation capability are refused rather
  than silently downgraded to the caller-asserted flag. Default is `off`, which
  keeps the previous behavior exactly.
- `telegram-mcp config` prints a ready-to-paste client entry. It emits the key
  the target client actually reads — VS Code names it `servers`, and an
  `mcpServers` block there is ignored in a way that looks like a broken server —
  and `--docker default` pins the running version rather than a floating tag.
- `MCP_AUDIT_FILE` optionally appends forced JSONL records to a persistent local
  audit trail. Arguments remain absent unless explicitly enabled and recognized
  credential fields are then redacted. Dispatch-level fallback auditing and
  explicit categories cover all 110 registered tools, including account-routing
  denials and consistent policy-block outcomes.
- Bash protocol smokes now mirror the PowerShell STDIO and Streamable HTTP
  checks, and the multi-client guide documents one shared HTTP daemon for
  clients that use the same TDLib session.

### Changed

- The default tool profile is now `reader`; exposing the complete administration
  surface requires an explicit `MCP_TOOL_PROFILE=all`. Startup emits a prominent
  warning when that full profile is combined with `MCP_READ_ONLY=false`.
- Destructive-operation documentation now calls `confirmed: true` a caller
  acknowledgement. It is not proof of human approval; MCP hosts remain
  responsible for human-in-the-loop UX.
- The public benchmark now covers better-telegram-mcp 4.17.0's compact
  action-dispatch and HTTP/OAuth trade-offs using pinned primary sources.

### Fixed

- The `inbox` profile now exposes `list_chats`, `get_chat`,
  `list_chat_folders`, and `get_chat_folder`, matching the documented inbox
  summarization recipe.
- STDIO shutdown can promote an in-flight clean exit to a fatal exit code,
  freezes one final reason, and emits a stable stderr line before one halt. The
  smoke tests now fail unless stdin closure produces a natural exit within the
  lifecycle deadline.
- Authentication readiness fails closed: an initial failure cannot be erased
  by a late READY signal, while an unexpected session close after READY blocks
  later calls instead of forwarding them to a closed client. Session-lock
  recognition now requires a typed TDLib error, code 400, the `td.binlog`
  target, and known ownership wording.
- Container images now set both the single-account TDLib path and the
  multi-account application-data root to the persistent `/data/tdlib-data`
  volume.
- A termination signal now ends the process under the same deadline as stdin
  EOF. Spring's shutdown hook has none, and closing the context runs TDLib's
  `@PreDestroy`, which can block — so `docker stop`, `compose down`, systemd and
  Kubernetes all waited out their grace period and escalated to SIGKILL,
  stopping TDLib mid-write. Measured against 1.10.0: `docker stop` took the full
  timeout and exited 137.
- The container health probe now matches the transport it was started with. A
  STDIO container owns no listener, so probing the actuator reported every
  healthy stdio container as unhealthy for its whole lifetime and buried a real
  failure among permanent ones.
- Running the server as a STDIO container is now documented with a pinned
  `-stdio` tag. `docker run` never re-pulls an image the machine already has, so
  a client configured against a floating tag keeps starting the build it first
  cached — including builds predating the 1.10.0 lifecycle fix, whose orphaned
  containers hold `td.binlog` and make every later start fail to lock the
  session.

## 1.10.0 - 2026-07-25

### Fixed

- The STDIO server now exits when its client closes stdin. A stdio client ends
  a session by closing the stream without sending a signal, and `docker run` is
  only a CLI client to the daemon — its death does not stop the container. The
  server kept running, held the TDLib session (`td.binlog`) locked, and every
  subsequent start failed to authenticate. Stdin is wrapped, never read by the
  server itself, so the MCP transport keeps exclusive ownership of the stream;
  end-of-stream closes the Spring context and, because TDLib runs non-daemon
  threads that would otherwise keep the process alive, a five-second deadline
  then halts the JVM. HTTP deployments are unaffected.
- Startup no longer blocks on Telegram authentication, so `initialize` is
  answered immediately. Waiting for a login could exceed a client's handshake
  timeout (60s in Claude Desktop), which marked the server failed and dropped
  the connector. The wait moved to the first tool call and is bounded by the
  new `tdlib.auth.ready-timeout` (`TDLIB_AUTH_READY_TIMEOUT`, 45s); an account
  that is not ready now surfaces as a readable tool error.
- A TDLib session locked by another process fails fast with an actionable
  message on stderr and exit code 2. TDLib reports the locked binlog outside
  the authorization-state machine, so it previously surfaced only as
  `WARN Unhandled exception!` while the server waited out its 90-second
  authentication timeout.

### Changed

- README and `docs/CLI_AND_STDIO.md` document the stdio lifecycle contract, and
  the Claude Desktop instructions note that Microsoft Store (MSIX) builds keep
  `claude_desktop_config.json` under
  `%LOCALAPPDATA%\Packages\Claude_pzs8sxrjxfjjc\LocalCache\Roaming\Claude\`
  rather than `%APPDATA%\Claude`. Settings → Developer → Edit Config opens the
  right file for either build.

## 1.9.0 - 2026-07-08

### Security

- `send_message`, `reply_to_message`, `edit_message`, and `download_media` now
  pass through the operation guard like every other write tool. Previously the
  per-tool guard call was missing, so anti-spam rate limits, duplicate-message
  detection, and operator-configured confirmation lists were not enforced for
  the highest-volume messaging tools. A new source-level regression test
  (`WriteToolGuardCoverageTest`) fails the build if any write tool skips the
  guard.
- `send_message`, `reply_to_message`, and `edit_message` are now audit-logged;
  outbound messages previously produced no audit entries.
- Entity resolution caching (`self`, `@username`, `+phone`) is now scoped per
  Telegram account. In multi-account mode, the canonical `self` chat of one
  account could previously be served from another account's cache entry and
  misroute a message across accounts.
- Read-only mode is enforced at tool dispatch in addition to tool hiding, so a
  client replaying a cached tool list (or a future registration regression)
  cannot execute a write tool.
- `register_internal_chat` is destructive and confirmation-gated: it loosens
  anti-spam rate limits for the target chat, so a prompt-injected call must
  not silently weaken its own guardrails.
- Audit-log redaction is recursive and token-based; credentials nested inside
  object or array arguments (`bot_token`, `proxyPassword`, `phone_number`) are
  redacted instead of only top-level exact-name keys.
- With the STDIO transport, an unresolved TDLib login parameter now fails
  authentication with actionable guidance instead of prompting on
  stdout/stdin, which corrupted the JSON-RPC stream and could swallow
  protocol frames.
- Multi-account startup validation now also rejects shared or nested
  downloads directories, not only shared TDLib database directories.

## 1.8.2 - 2026-07-04

### Changed

- Project home is `tolboy/telegram-mcp-tdlib`: container images are published
  as `ghcr.io/tolboy/telegram-mcp-tdlib` and the MCP Registry entry is
  `io.github.tolboy/telegram-mcp-tdlib`.
- The metadata namespace that marks untrusted Telegram content in tool results
  is now `io.github.tolboy/untrusted-content`. No functional tool changes.

## 1.8.1 - 2026-07-02

### Added

- Optional MCP Inspector compose profile (`docker compose --profile inspector
  up`) for browsing and exercising the full tool surface interactively.
- `ReadmeToolInventorySyncTest` fails the build when the README tool tables
  and the registered tool inventory diverge in either direction.

## 1.8.0 - 2026-07-02

### Added

Nine tools adopted from the public Telegram MCP benchmark
(see `docs/PUBLIC_BENCHMARK_AND_ROADMAP.md`, 2026-07-02 review), raising the
surface to 110 tools:

- `vote_poll` and `close_poll` complete the poll lifecycle; closing is
  destructive and confirmation-gated.
- `get_message_viewers` exposes Telegram read receipts for outgoing messages
  in small groups.
- `set_chat_description` and `set_slow_mode` extend community administration;
  slow mode is confirmation-gated because it limits every non-admin member.
- `list_invite_links` and `revoke_invite_link` complete invite-link
  administration; revocation is destructive and confirmation-gated.
- `reorder_chat_folders` completes the chat-folder family.
- `get_common_chats` lists groups and channels shared with a user.

All new write tools respect read-only mode, tool profiles, chat allow-lists,
audit logging, and anti-spam limits. Raw MTProto access, story posting, and
ownership transfer seen in other servers were deliberately not adopted.

## 1.7.1 - 2026-07-02

### Fixed

- Stabilized live Telegram tool workflows exercised against a real account.

### Changed

- Default application and MCP transport log levels are now `INFO`; enable
  `DEBUG` explicitly when diagnosing an issue.
- Moved the container hotfix/restore helper scripts from the repository root
  into `scripts/`.
- Documented the previously untracked `1.1.1` and `1.5.0` tags and refreshed
  the README release narrative and project structure.

## 1.7.0 - 2026-07-01

### Added

- Added STDIO transport, stable `serve/auth/session/version` CLI and a
  loopback-only browser authentication wizard.
- Added backward-compatible structured tool output, untrusted-content
  annotations and presentation-control Unicode escaping.
- Added exact tool allow/deny filters and optional OAuth 2.1 resource-server
  mode with RFC 9728 metadata and account claims.
- Added runtime-inclusive `jpackage` release images, Homebrew/Scoop manifests,
  official MCP Registry metadata and dual HTTP/STDIO OCI images.

- MCP behavior annotations for safer client-side tool presentation and retry
  decisions.
- Strict read-only classification for `download_media`, which writes to the
  local filesystem.
- `get_message_link` for TDLib-native message and media-album links.
- Interactive QR/phone authentication guide and configurable Telegram device
  identity.
- Gradle wrapper 9.6.1 with distribution checksum verification.

### Security

- Interactive auth is keyless only on loopback; private-network and container
  peers must authenticate.
- Third-party GitHub Actions are pinned to immutable commits.
- Prompt-injection policy now stays with the MCP host that consumes untrusted
  Telegram content; the connector's optional input denylist defaults to empty.

### Changed

- Reduced the connector contract to the canonical `self` chat identifier;
  natural-language aliases belong in the calling host or router.
- Moved promotion-policy interpretation out of the transport connector. Clients
  should evaluate raw chat descriptions and pinned messages in their own locale.
- Relative anti-spam state now resolves under the platform application-data
  directory, and dependency resolution no longer consults `mavenLocal()`.

## 1.5.0 - 2026-06-30

- Interim tag on the road to 1.7.0. Its changes — STDIO transport, CLI,
  interactive auth wizard, OAuth resource-server mode, structured output, and
  runtime-inclusive release images — are documented under 1.7.0, which is the
  supported release of that line.

## 1.4.1 - 2026-06-26

- Added SBOM, provenance attestation, keyless container signing, CodeQL,
  dependency review, and cross-platform MCP surface smoke workflows.

## 1.4.0 - 2026-06-24

- Added reader, inbox, community-admin, and research tool profiles.

## 1.3.1 - 2026-06-23

- Kept build, descriptor, JAR, image, and release-bundle versions consistent.

## 1.3.0 - 2026-06-23

- Added privacy, bot-command, group-permission, chat-folder, scheduled-message,
  session-maintenance, and verified release-bundle workflows.

## 1.2.0 - 2026-06-21

- Moved the public namespace to `dev.telegrammcp.server`, removed private
  product coupling, and added native Telegram voice-note transcription.

## 1.1.1 - 2026-06-21

- Patch follow-up to 1.1.0 with packaging and metadata corrections.

## 1.1.0 - 2026-06-20

- Added isolated multi-account sessions, account-scoped API keys, and
  cross-platform TDLight native packaging.

## 1.0.0 - 2026-06-20

- Established the public Streamable HTTP and MCP SDK 2.0 baseline.
