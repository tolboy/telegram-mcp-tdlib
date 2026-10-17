# Session clear safety and dependency updates

Date: 2026-10-17. This patch release follows 1.17.2. It contains no
credentials or Telegram message content.

## Session clear

[PR 37](https://github.com/tolboy/telegram-mcp-tdlib/pull/37) fixes
[issue 27](https://github.com/tolboy/telegram-mcp-tdlib/issues/27): lock
detection stopped at the first existing TDLib lock candidate, so a held
`td.binlog` next to a free `td.sqlite` could be reported as unlocked and
`session clear` could delete part of a live session.

Lock inspection now probes every existing candidate and reports the session as
locked if any is held, or unavailable if a probe fails and none is held. Clear
first moves the session directory atomically to a sibling staging path,
re-checks the lock there and moves it back if a lock appeared. Nothing is
deleted when the move fails. Doctor lists leftover staging paths, and clear
refuses retries until they are recovered manually. Two regression tests cover
the held-binlog case for `doctor` and `clear`; the PR records manual Linux and
Windows 11 verification against the 1.17.2 artifact.

## Dependency updates

- Kotlin JVM and Spring plugins 2.4.0 to 2.4.21
  ([PR 22](https://github.com/tolboy/telegram-mcp-tdlib/pull/22)).
- Caffeine 3.2.4 to 3.3.1
  ([PR 35](https://github.com/tolboy/telegram-mcp-tdlib/pull/35)).
- Gradle wrapper 9.7.1 to 9.8.1
  ([PR 36](https://github.com/tolboy/telegram-mcp-tdlib/pull/36)).
- Nine pinned GitHub Actions updates, including setup-java 6.0.1,
  setup-gradle 6.4.0, docker/build-push-action 7.4.0 and CodeQL 4.38.3
  ([PR 30](https://github.com/tolboy/telegram-mcp-tdlib/pull/30)).

## Verification

The [master CI run](https://github.com/tolboy/telegram-mcp-tdlib/actions/runs/39036780253)
and [Security run](https://github.com/tolboy/telegram-mcp-tdlib/actions/runs/39036780273)
for merge commit `8533766` both passed.

Locally on JDK 25, the verify-release gate
`./gradlew test bootJar verifyNativeRuntimeCoverage verifyReleaseMetadata -PreleaseVersion=1.17.3 --no-daemon --no-build-cache`
passed: 817 tests, 0 failures, 1 skipped; native runtime coverage for
linux x64/arm64, windows x64 and macOS arm64; release metadata 1.17.3. The
Python bridge unit tests, the independent result smoke against the 1.17.3 jar
(no authorized Telegram session) and skill packaging also passed.

## Scope

No tool surface, schema, configuration or packaging changes. The companion
skill remains 0.1.1, and documented minimums stay at server 1.17.2. Platform
packages, container image and published release verification are produced by
the tag workflow and are not covered by this report.
