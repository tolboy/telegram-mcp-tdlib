# Release preparation for 1.20.0

Prepared on 2026-11-09. The release includes the TDLib lifecycle and MCP
cancellation changes in [PR #64](https://github.com/tolboy/telegram-mcp-tdlib/pull/64)
and the Actions updates in [PR #63](https://github.com/tolboy/telegram-mcp-tdlib/pull/63).

## Upgrade notes

- Optional numeric query fields such as `limit` and `from_message_id` use their
  usual defaults when omitted or set to JSON `null`. Required integers and
  supplied numeric mutation arguments reject `null`. Fractional, overflowing,
  and malformed values are rejected instead of silently changing the operation.
- A group- or world-writable POSIX ancestor without the sticky bit can prevent
  session startup. This includes an existing `775` data ancestor such as
  `~/.local/share`. Check the path in the startup error. Remove write access
  for group and others on operator-owned directories that can be private, or
  move state to a protected path. Secret-file checks cover the file and its
  immediate parent. See the [upgrade check](SESSION_SECURITY_AND_RETENTION.md#upgrade-check-for-existing-installations).
- `session clear` keeps an unencrypted copy of session credentials in a private
  backup tree by default. Use `--yes --no-backup` to skip the new copy. Revoke
  compromised sessions in Telegram and review earlier backups separately. See
  [session maintenance](SESSION_MAINTENANCE.md).

## Candidate verification

- The final PR #64 changes passed 1,283 Windows JVM tests with six skipped and
  both Windows native tests. GitHub CI passed the native matrix on Windows x64,
  Linux x64 and ARM64, and macOS ARM64. The offline CLI smoke passed both
  cleanup modes, and live STDIO accepted a null history limit and cursor.
  An earlier live text send verified the final message ID and receipt replay
  across a restart.
- `bootJar verifyNativeRuntimeCoverage verifyReleaseMetadata` passed with
  `-PreleaseVersion=1.20.0`. The independent MCP SDK reported 1.20.0 over STDIO,
  Streamable HTTP, and the bridge and verified a daemon restart.
- The Windows x64 archive passed launcher version, session doctor, STDIO,
  managed-daemon lifecycle, and both Windows Job Object scenarios.

## Before tagging

Complete the third-party distribution review. The inspected TDLight Java,
API, and native JARs lack standalone license or notice files. The app-image
packager adds the project license to its support directory. Verify and include
the license, notice, and source information for the shipped components in the
release artifacts. Use the [TDLight Java upstream](https://github.com/tdlight-team/tdlight-java)
and the [native build upstream](https://github.com/tdlight-team/tdlight-java-natives)
to match the files to the packaged versions.
