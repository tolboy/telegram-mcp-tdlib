# Event waiting, message context and Telegram request reliability

Date: 2026-10-26. This minor release follows 1.17.3 and grows the full tool
surface from 115 to 116 tools.

## Included changes

- [PR 42](https://github.com/tolboy/telegram-mcp-tdlib/pull/42): TDLib 1.8.68,
  explicit Telegram error classification, scoped flood gates, a shared tool-call
  deadline and per-account concurrency limits. The final review fixes prevent
  unsafe retries after partial delivery or an earlier write and propagate the
  deadline into public-search coroutine workers.
- [PR 43](https://github.com/tolboy/telegram-mcp-tdlib/pull/43): resumable
  `wait_for_changes`, richer message context and strict formatting validation.
- Already merged dependency updates: MCP Java SDK 2.1.0, Gradle 9.8.2 and
  pinned GitHub Actions (#39, #40, #41).

Release preparation synchronizes `server.json`, its OCI package tag, the
116-tool description, the connector-discovery example and the dated changelog.
The companion skill remains 0.1.1 with minimum server version 1.17.2.

## Review and integration evidence

Both feature PRs passed their five GitHub checks on the exact reviewed heads
(`d8ec751` and `a9bab7f`). The three inline findings on PR 42 were traced to
their fixes and regression tests; their GitHub discussion flags were still
unresolved at merge time. PR 43 had no GitHub review threads. The merge order
preserves the dependency: PR 42 first, then PR 43 retargeted to `master`.

The combined master commit `b004063` passed
[CI](https://github.com/tolboy/telegram-mcp-tdlib/actions/runs/40321921905)
and [Security](https://github.com/tolboy/telegram-mcp-tdlib/actions/runs/40321921701).
These include the full Linux JVM suite, native/release metadata checks,
independent MCP and bridge checks, production Docker build, Windows daemon
lifetime verification and CodeQL.

## Local release candidate verification

Verification uses Temurin 25.0.2 and a separate `build-release-1.18.0` directory.

- `bootJar`, `verifyNativeRuntimeCoverage` and `verifyReleaseMetadata` passed
  with `-PreleaseVersion=1.18.0`. All four supported native classifiers are
  present, using BOM-managed `tdlight-natives 4.0.597`.
- Two full JVM runs each completed 1000 tests with one failure and two skips.
  The failure was the unchanged Windows `SessionMaintenanceTest` case
  `lock acquired during staging restores session without deleting data` at
  its restored-path assertion. It also failed once in isolation, then all
  16 class tests passed during a diagnostic run. The original source and test
  are unchanged from 1.17.3; the initial assertion did not capture the caught
  exception, so no specific filesystem failure cause was established. Temporary
  diagnostic changes were removed. The second full run disabled Kotlin
  incremental compilation and reproduced only that same assertion failure.
  The local Windows full-suite gate is therefore not claimed as passing;
  the complete Linux CI suite and Windows daemon lifetime gate passed.
- Five Python bridge/packaging tests and companion skill ZIP packaging passed.
- Independent Python MCP SDK checks against the 1.18.0 JAR passed over STDIO,
  HTTP and the optional bridge: success/error parsing, output schemas,
  annotations, profile inventory and isolated daemon restart. Each transport
  reported server version 1.18.0 and 36 read-only inbox-profile tools.

## Scope

The local protocol checks use isolated storage and no Telegram credentials.
They do not establish a new live Telegram or native AI-host acceptance result.
The prior authenticated feature checks are recorded in PR 43.

The tag-triggered release workflow independently gates publication on full
tests, cross-platform profile smoke, four runtime packages, container lifecycle
checks against candidate digests, signing and provenance. Published artifacts,
container tags and MCP Registry delivery must be checked after that workflow;
the local results above do not claim those external publications succeeded.
