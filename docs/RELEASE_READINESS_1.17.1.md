# 1.17.1 release verification

Date: 2026-10-15. Cursor is excluded from the required native gate at the user's
request. No new broad marketplace compatibility claim is made.

**Superseded for marketplace use by 1.17.2.** A subsequent real host restart
revealed that the Windows daemon inherited its launcher's kill-on-close Job
Object. The lifecycle checks below restarted the daemon, but did not close the
host Job Object. See [the regression and fix](RELEASE_READINESS_1.17.2.md).

## Implemented

- Remove optional content priority while preserving user audience and untrusted
  metadata; guard the serialized success/error form with a JVM regression test.
- Build manifest routing hints from the advertised tool surface.
- Distribute the optional Python MCP SDK tools bridge with key-file auth, safe
  endpoint handling, bounded requests and no replay on a lost HTTP connection.
- Keep bundled Java child-process launchers in app images. Clean-package testing
  reproduced a missing javaw.exe failure before this packaging fix.
- Add independent success/error/schema checks across STDIO, HTTP and the bridge
  to CI and the tagged release gate. Test extracted packages' daemon lifecycle.
- Package the free read-only companion skill with eight explicit public files,
  a SHA-256 sidecar, minimum server version and host-specific setup instructions.

## Executed locally

| Check | Result and boundary |
| --- | --- |
| JVM tests | 804 tests, 802 passed, two skipped, zero failures/errors; no live account credentials |
| Build metadata and native coverage | Pass for 1.17.1; all four supported native classifiers present |
| Independent Python SDK, runnable JAR | Manifest success, offline account error, output schemas, annotations and matching tool catalog pass over STDIO/HTTP/bridge |
| Bridge corpus | Four tests pass in a new Python 3.14 venv; paginated discovery, Unicode, large IDs, null/empty/partial results, text-only preservation, error preservation, fatal HTTP/no retry, key denial and URL restrictions |
| Cross-platform skill ZIP | A fifth Python test confirms identical archive bytes for LF/CRLF checkouts and exclusion of an injected synthetic private file |
| Windows runtime archive | Built, extracted outside the checkout, version/doctor/JSON-only STDIO and bounded EOF shutdown pass |
| Extracted daemon lifecycle | Duplicate start, two separate sessions, auth rejection, graceful stop and restart pass |
| Extracted runtime without developer Java/PATH | Independent SDK success/error/schema checks pass through all three routes; offline daemon restart passes with JAVA_HOME removed and PATH restricted to Windows directories |
| Skill ZIP | Frontmatter validator, configuration syntax/policy checks, explicit entries, CRC and byte equality pass |

The failed package before the launcher fix was retained locally; it is not the
candidate for publication. Test data and keys are temporary/offline. The live
Telegram daemon was not used by these automated checks.

Dependency review rejected the prototype's preinstalled Python SDK 1.22.0.
The distributable requirement is pinned to **1.30.0** and all five Python tests
were repeated with that version. Relevant upstream advisories include
[principal isolation](https://github.com/advisories/GHSA-jpw9-pfvf-9f58) and
[HTTP defaults](https://github.com/advisories/GHSA-9h52-p55h-vw2f); the pin is
updated rather than suppressing the dependency-review gate. Native historical
reports retain the actual version they used.

Native model evidence remains the earlier [Codex skill walkthrough](../skills/VALIDATION.md)
and [Hermes/Claude Desktop runs](harness-tests/RESULTS_2026-10-15.md), performed
with an existing authorized account on the priority-fixed development build.
The distributable bridge has subsequent independent SDK/corpus coverage; do not
silently relabel those earlier host runs as a fresh native 1.17.1 installation.

## Publication boundaries

The [1.17.1 release workflow](https://github.com/tolboy/telegram-mcp-tdlib/actions/runs/38764754993)
completed successfully. All five surface checks and all four runtime package
jobs passed, followed by container health/lifecycle checks, attestations,
signatures, MCP Registry publication and the
[GitHub release](https://github.com/tolboy/telegram-mcp-tdlib/releases/tag/v1.17.1).
The release contains the runnable JAR, four runtime packages, bridge ZIP and
skill ZIP with its SHA-256 sidecar.

The downloaded skill archive passed CRC and sidecar verification. Its eight
extracted files match the locally validated package byte for byte. DEFLATE
output differs between the local and CI compression libraries, so archive
digests are not guaranteed across different zlib implementations. The canonical
published skill ZIP SHA-256 is
`eb486c9654f08f971807b4bca14f24e54ca25fea2348f6b93cded08b5ef0d9e8`.
This was the canonical 1.17.1 archive; the marketplace submission now requires
the updated skill and server described in the 1.17.2 report.

The installed Windows runtime also reused the existing authorized account
before and after a graceful restart, verified through the independent SDK.
This does not replace a fresh native-host installation test of the final build.
Fresh Telegram authorization, malicious-message host fixtures,
Agensi delivery and marketplace skill discovery are not verified by these
offline checks. Public support language keeps these limits explicit.

The Agensi submission page timed out in the available browser. No marketplace
upload or submission was completed; the archive and listing are prepared.

The ready-to-submit listing text is [skills/AGENSI_LISTING.md](../skills/AGENSI_LISTING.md).
The skill archive is generated with `python scripts/package-telegram-skill.py`;
the release workflow attaches it and the optional tools-bridge archive.
