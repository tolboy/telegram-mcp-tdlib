# Startup termination patch 1.19.1

Date: 2026-11-02. This patch follows 1.19.0; the full inventory remains 126
tools. No configuration migration is required. The companion skill is 0.1.3,
with minimum compatible server version 1.17.2.

## Included changes

- [PR #53](https://github.com/tolboy/telegram-mcp-tdlib/pull/53): remove Spring's
  late JVM shutdown-hook registration from server startup. The existing
  bounded `ServerShutdown` path owns termination, including a signal received
  while Spring is starting. Add a deterministic child-JVM regression/control
  test and sweep the STDIO smoke across six startup-signal delays.
- [PR #54](https://github.com/tolboy/telegram-mcp-tdlib/pull/54): record the
  publication and verification of 1.19.0 without changing its artifacts.
- [PR #55](https://github.com/tolboy/telegram-mcp-tdlib/pull/55): update the
  read-only companion skill to 0.1.3 and record marketplace submissions.
  Marketplace approval and delivered contents remain separately unverified.
- [PR #56](https://github.com/tolboy/telegram-mcp-tdlib/pull/56),
  [#57](https://github.com/tolboy/telegram-mcp-tdlib/pull/57),
  [#58](https://github.com/tolboy/telegram-mcp-tdlib/pull/58), and
  [#59](https://github.com/tolboy/telegram-mcp-tdlib/pull/59): actual terminal
  recording against 1.19.0, versioned media URLs, and a reviewed architecture
  diagram. The recording remains explicitly labelled with its captured version.

The patch does not replace the previously published 1.19.0 tag or assets.
Tag-triggered publication gates the full test suite, cross-platform MCP
surfaces, four runtime packages, container lifecycle checks, signatures,
attestations, the MCP Registry entry and GitHub release assets. Publication
must be verified from the completed workflow and downloadable artifacts.

## Local verification before the tag

- Java 25: `test bootJar verifyNativeRuntimeCoverage verifyReleaseMetadata`
  passed with `-PreleaseVersion=1.19.1`: 1167 cases, 4 skipped, 0 failures
  or errors. The Windows skips were two POSIX-terminal tests and two tests
  requiring symbolic-link privileges.
- Eleven Python tests passed; the companion skill package passed validation.
- The independent Python SDK checked STDIO, HTTP and bridge, plus an isolated
  daemon restart. It reported 1.19.1, 36 read-only inbox tools, valid output
  schemas and annotations. No Telegram credentials were used.

## Published verification, 2026-11-02

- PRs #53, #54 and #55 were merged in that order. The architecture diagram
  was already included through #59. [PR #60](https://github.com/tolboy/telegram-mcp-tdlib/pull/60)
  passed all five checks and prepared this release. Tag `v1.19.1` points to
  `a3accfd4e25917a19e9bdb6fbef48e066dac3b3f`.
- The [release workflow](https://github.com/tolboy/telegram-mcp-tdlib/actions/runs/41520206780)
  succeeded on its first attempt: full verification, all five cross-platform
  MCP profile checks, four runtime packages, both container lifecycle gates,
  artifact attestations, container signing, final-tag promotion and publication.
  The Linux complete-profile startup-signal smoke passed with the #53 fix.
- [GitHub Release v1.19.1](https://github.com/tolboy/telegram-mcp-tdlib/releases/tag/v1.19.1)
  is public, non-draft, non-prerelease and latest, with 12 assets. Its upgrade
  notes state that no configuration migration is required. The official MCP
  Registry reports 1.19.1 as active and latest.
- Downloaded JAR and Windows ZIP hashes match `release-manifest.json` and the
  GitHub asset digests. Both pass `gh attestation verify --repo tolboy/telegram-mcp-tdlib`.
  The downloaded Windows launcher's `version` command reports 1.19.1.
- The downloaded JAR passes independent STDIO, HTTP and bridge checks and
  daemon restart. The downloaded Windows runtime passes independent STDIO
  checks with its bundled Java. Both report 1.19.1, 36 read-only inbox tools,
  valid success/error schemas and annotations. These checks used no Telegram
  credentials; live account authorization and message operations were not
  repeated for this patch. Other OS packages were executed by CI, not locally.
- The skill ZIP contains exactly eight public files and identifies skill
  0.1.3; its checksum sidecar matches. The optional bridge ZIP contains its
  four expected files. Homebrew/Scoop manifests and the SBOM were downloaded
  and their hashes verified against the GitHub asset digests.
- Independently resolved GHCR tags: `1.19.1`, `1.19` and `latest` point to
  `sha256:694c768d2abcb70501b40b7229a96d2b7c2f0cf214e5ba63036e00a8400dd9ac`;
  `1.19.1-stdio` and `latest-stdio` point to
  `sha256:78d786e7974265cc4bc022ecd0dced09daefb01f56256cf2fe08989e4add9f5e`.
  The release runner completed container signing and provenance attestation.
  Independent local verification of container signatures is not claimed.

| Downloaded artifact | SHA-256 |
|---|---|
| `telegram-mcp-server-1.19.1.jar` | `f88a1d0dba840d984da8aedc3db60108d5d3ee0bfc4fc0ba8e97c96b390a6cb7` |
| `telegram-mcp-server-1.19.1-windows-x64.zip` | `78fb7f609550b2cebfa973cee140ba08f8cbf63f9dcf2468a5bd9639a2f00180` |
| `telegram-inbox-assistant.zip` | `83485f4944b0f6726128a20de7fcf1d800523bfeb8d8bfa4d8feb4e8417d8c9e` |
