# 1.17.1 release verification

Date: 2026-10-15. Cursor is excluded from the required native gate at the user's
request. No new broad marketplace compatibility claim is made.

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

The GitHub release workflow still requires its cross-platform and image gates
before publishing final assets. A local Windows pass does not mean those remote
jobs ran. Fresh Telegram authorization, malicious-message host fixtures,
Agensi delivery and marketplace skill discovery are not verified by these
offline checks. Public support language keeps these limits explicit.

The ready-to-submit listing text is [skills/AGENSI_LISTING.md](../skills/AGENSI_LISTING.md).
The skill archive is generated with `python scripts/package-telegram-skill.py`;
the release workflow attaches it and the optional tools-bridge archive.
