# Resources, ten new tools and approval hardening 1.19.0

Date: 2026-11-02. This minor release follows 1.18.1 and grows the full tool
surface from 116 to 126 tools.

## Included changes

- [PR 49](https://github.com/tolboy/telegram-mcp-tdlib/pull/49): additive
  destructive-tool configuration, high-risk approval scope and approval-link
  hardening.
- [PR 50](https://github.com/tolboy/telegram-mcp-tdlib/pull/50): ten media and
  moderation tools, including `send_album`, rich sends, join requests and chat
  statistics (116 to 126).
- [PR 51](https://github.com/tolboy/telegram-mcp-tdlib/pull/51): opt-in MCP
  resources and resource subscriptions, with authorization-scoped `chats`
  notifications and a 1 MiB `/mcp` body limit while subscriptions are active.
- Dependency updates: Spring Boot 4.1.2, Spring AI MCP 2.0.2,
  `gradle/actions/setup-gradle` 6.4.1 and `docker/login-action` 4.7.0
  (#45, #46).

Release preparation synchronizes `server.json` (version and stdio OCI tag)
and the dated changelog. The 126-tool description, README and
connector-discovery example were already updated by PR 50. The companion skill
remains 0.1.2 with minimum server version 1.17.2.

## Integration evidence

All five PRs were squash-merged in order #45, #46, #49, #50, #51 without
rebases. Each merge commit passed master CI and Security:

| PR | Master commit | CI | Security |
|---|---|---|---|
| #45 | `c10db94` | 41438070271 | 41438070292 |
| #46 | `0281750` | 41438783822 | 41438783459 |
| #49 | `e7ca1e4` | 41439509021 | 41439508995 |
| #50 | `b8ac8c6` | 41440691815 | 41440691984 |
| #51 | `9f882d3` | 41441622625 | 41441622639 |

## Local release verification

On `9f882d3` plus this release commit, with Temurin 25 and
`-PreleaseVersion=1.19.0`:

- `test bootJar verifyNativeRuntimeCoverage verifyReleaseMetadata` passed:
  1165 tests, 1 skipped, 0 failures. All four native classifiers
  (tdlight-natives 4.0.597) are present.
- Eleven Python tests passed. The independent STDIO/HTTP/bridge result smoke
  reported server 1.19.0 and 36 read-only inbox-profile tools; without an
  authorized session, tool calls returned the structured
  `TELEGRAM_UNAVAILABLE` error as expected.
- Companion skill packaging passed.

Cross-platform app images, container images, signatures, the MCP Registry
entry and the GitHub Release are produced and gated by the `v1.19.0` tag
workflow. The follow-up below records the actual publication results.

## Published verification, 2026-11-02

- [PR #52](https://github.com/tolboy/telegram-mcp-tdlib/pull/52) was squash-merged
  after all checks passed. The annotated tag `v1.19.0` points to
  `105d7e0c606bc841a4fce44bd3fd497fa8087507`.
- The [release workflow](https://github.com/tolboy/telegram-mcp-tdlib/actions/runs/41446953950)
  succeeded on attempt 2. Attempt 1 failed only in the STDIO smoke's
  startup-signal check: Spring Boot threw `Shutdown in progress` when the
  1.5 s signal landed during startup. Only the failed jobs were re-run; no
  artifact was published by attempt 1. The race predates 1.19.0 and is fixed
  separately in [PR #53](https://github.com/tolboy/telegram-mcp-tdlib/pull/53).
- The [GitHub Release](https://github.com/tolboy/telegram-mcp-tdlib/releases/tag/v1.19.0)
  is public, not a draft and marked latest, with 12 assets. Upgrade notes were
  added to the release description. The official MCP Registry API returns
  1.19.0 as active and latest.
- Downloaded artifact hashes match `release-manifest.json`. The JAR, the
  Windows ZIP and the Linux x64 archive pass
  `gh attestation verify --repo tolboy/telegram-mcp-tdlib`.
- The published JAR passes the independent result smoke over STDIO, HTTP and
  bridge plus daemon restart, reporting server 1.19.0 and 36 read-only
  inbox-profile tools. The downloaded Linux x64 runtime reports `1.19.0` and
  passes the STDIO smoke with its bundled Java. No Telegram credentials were
  used.
- Final OCI indices: `1.19.0` and `latest` resolve to
  `sha256:bf49c80b55cea19996b850ded86bef0a01d96581a7481c2f1f7b352af7a4d267`;
  `1.19.0-stdio` and `latest-stdio` resolve to
  `sha256:238c751a3d9d27c687d62f3e753ac2440fa2615eb784c8221a71319b43bc28ed`.
  Signing was verified by the release runner, not re-run locally.

| Downloaded artifact | SHA-256 |
|---|---|
| `telegram-mcp-server-1.19.0.jar` | `93c5a6f98cf6ad5f3270618eef132b0185196bd170e28320c96dcec604d5f354` |
| `telegram-mcp-server-1.19.0-linux-x64.tar.gz` | `5b920320091a28aa0e0c35f7c15d00185f163a23b9adb1398add5f506f796775` |
| `telegram-mcp-server-1.19.0-linux-arm64.tar.gz` | `644a4e603d1899c416fd297c30f788a2fb3844a775a0539ab6c4a95f84b29ff8` |
| `telegram-mcp-server-1.19.0-macos-arm64.tar.gz` | `3c384809b0c706b6854e2627f747daa1839b0ad9d090fec14e2515a303963300` |
| `telegram-mcp-server-1.19.0-windows-x64.zip` | `c73806b46996ad89338cc777111eecd54f440a275b1a3d251dabc786d49c201c` |
| `telegram-inbox-assistant.zip` | `8263aa81e5f2bc4855846e133c77d490f77a1920c7b47613a55e7a28b1273edd` |

The skill ZIP is byte-identical to the one published with 1.18.1, so the
Inbox Assistant (0.1.2) and its Agensi listing are unchanged by this release.
The Linux arm64, macOS and Windows runtimes were not executed locally; CI
covers them.
