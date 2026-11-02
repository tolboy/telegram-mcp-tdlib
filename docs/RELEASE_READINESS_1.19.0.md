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
workflow.
