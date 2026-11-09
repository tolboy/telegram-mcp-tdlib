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

## Distribution notices

The [notice index](../licenses/tdlight/NOTICE.md) records the wrapper, API,
and JNI source revisions and the linked OpenSSL and zlib versions. All four
JNI binary payloads match upstream build run 597 byte for byte. The Linux
Maven JARs differ from the CI archives in their JAR and Maven metadata.

The JAR gate checks that the reviewed TDLight versions and license files are
present. The app-image packager verifies that its extracted notices match the
original files. The container build copies the same directory. A launch from
the documented exploded classpath reported 1.20.0 and passed the MCP STDIO
handshake and tool discovery.
