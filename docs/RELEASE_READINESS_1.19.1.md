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
