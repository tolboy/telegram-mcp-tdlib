# Changelog

Notable changes to Telegram MCP Server are documented here. The project follows
[Semantic Versioning](https://semver.org/).

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
