# MCP hardening patch release 1.18.1

Date: 2026-11-01. The tool inventory remains 116. Companion skill: 0.1.2,
minimum compatible server: 1.17.2.

This release contains the reviewed landscape follow-ups and their corrective
refactor. See [review evidence](HARDENING_REVIEW_2026-11-01.md) for findings,
tests, live coverage and known limits.

## Migration

Existing write deployments with `MCP_DESTRUCTIVE_APPROVAL=off` must choose
`auto`, `elicitation`, or `loopback`, or explicitly accept model-supplied
confirmation using `MCP_ALLOW_MODEL_CONFIRMED_WRITES=true`. Human approval
applies to destructive tools, not every mutation. Read-only defaults remain.

HTTP validates Origin/Host and binds sessions to their authenticated owner.
Configure exact additional origins/hosts for reverse proxies. Sessions expire
after 24 idle hours; clients must initialize again on 404. Tools/list and
manifest entries are narrowed by grants and accessible accounts, including
STDIO's `stdio` identity. Invalid/shared-write anti-spam overrides now prevent
startup. Check policy files and recognized Windows shared-group ACLs.

The release platform inventory smoke explicitly uses `elicitation`, so it
does not bypass the new startup guard or execute Telegram mutations.

## Local verification before publication

- Full clean JVM run: 1042 passed, 2 unsupported Windows symlink cases skipped.
- Python suite: 11 passed. Skill packaging/validation passed.
- Release-version bootJar/native/release metadata gates passed for 1.18.1.
- Independent SDK STDIO/HTTP/bridge and daemon restart checks passed on the
  reviewed source. Live baratnor read-only STDIO/HTTP checks passed against
  one kodobeast history record; no messages sent.
- Focused conformance: six checks succeeded, but the Windows Node/libuv
  runner crashed; end-to-end conformance is not claimed as passing.

Tag-triggered CI separately gates four runtime packages, signed container
digests, artifact checksums and registry publication. Presence of this file
does not imply that remote publication or post-release verification succeeded.
The follow-up below records the actual publication results.

## Published verification, 2026-11-01

- [PR #47](https://github.com/tolboy/telegram-mcp-tdlib/pull/47) merged after all
  five checks passed. Tag `v1.18.1` points to `45d02828ec67e76b2d28205ba8a87086acf4ee0f`.
- [Release workflow](https://github.com/tolboy/telegram-mcp-tdlib/actions/runs/41301731106)
  completed successfully: full tests, five cross-platform profile smokes, four
  bundled runtimes, both container lifecycle gates, attestations/signatures,
  final-tag promotion, MCP Registry and GitHub Release publication.
- [GitHub Release](https://github.com/tolboy/telegram-mcp-tdlib/releases/tag/v1.18.1)
  is public and not a draft. The official MCP Registry API returns server version
  1.18.1. Migration notes were added to the release description.
- Downloaded JAR and Windows ZIP hashes match `release-manifest.json`. Both
  pass `gh attestation verify --repo tolboy/telegram-mcp-tdlib`, yielding verified
  SLSA provenance from this repository.
- The published JAR passes independent SDK checks over STDIO, HTTP and bridge,
  plus isolated daemon restart. The downloaded Windows runtime passes the
  independent STDIO check using its bundled Java. Both report 1.18.1, 36
  read-only inbox tools, valid success/error schemas and annotations. No live
  Telegram credentials were used for these post-publication checks.
- Final OCI indices resolve to `sha256:feaa91d915b4a8ed5646c76928c833679c624353914a02dce15f6990989f1a29`
  (runtime) and `sha256:0c6cfa9ddf4bf7ca72bf118da393c2d7ff8165987d81ac32c089588b43b314aa`
  (stdio). Container execution/signing were verified by the release runner;
  local verification resolved the published indices, without rerunning cosign.

| Downloaded artifact | SHA-256 |
|---|---|
| `telegram-mcp-server-1.18.1.jar` | `bd0a5dc05baaed5bbe90389f591d09bd594b2ab9b0ac729f0010afb9e2da8d2a` |
| `telegram-mcp-server-1.18.1-windows-x64.zip` | `698385e68e4ff7063cc4a46799437d0713a3e39b6a7a5330e783e27ea46c7dc0` |
| `telegram-inbox-assistant.zip` | `8263aa81e5f2bc4855846e133c77d490f77a1920c7b47613a55e7a28b1273edd` |

Linux/macOS runtime execution is covered by CI; those release archives were not
executed locally on Windows. Local evidence is retained under
`build-release-1.18.1/verification/` and `published/` (not committed).

## Agensi submission

The official release skill ZIP was compared with the reviewed local 0.1.2
package: all eight extracted files are identical. Different zlib implementations
produce different compressed bytes, so the canonical release hash above differs
from the earlier Windows-local ZIP hash in the review report.

The existing listing was edited in the user's Edge session: ZIP replaced,
change notes added, description updated to 0.1.2 with recommended server 1.18.1
and minimum server 1.17.2. Name, free price and read-only scope were preserved.
After final submission, Agensi displayed "Changes saved" and stated that listing
changes are live but the new file goes live after security scan and review.
The previous approved package remains live in the meantime. Admin review normally
takes 24–48 hours according to the UI. New-package approval and downstream
marketplace delivery are not claimed as complete.

The publication follow-up also restores historical Unicode punctuation in
CHANGELOG.md that the local Windows release-preparation script misdecoded.
This documentation correction does not change runtime artifacts or rewrite
the published immutable tag.
