# Berloga live validation — 1.17.0-rc.1

Tested on Windows with Docker Desktop (Linux amd64), Java 25 and the running
Berloga Tauri launcher. Calls traversed the Tauri `router_request` command,
hybrid router and authenticated Telegram MCP connector. The existing account
and TDLib volume were retained. Only the user's explicitly authorized test
recipient received messages. No credentials or private history are included here.

## Results

| Check | Observed result |
|---|---|
| Discovery | 115 tools; candidate reports `1.17.0-rc.1` |
| Compact reads | Inbox and target conversation normalized data fit the 512-character budget; truncation explicit |
| Recoverable send | SENT receipt; identical keyed replay returns the same message ID with `replayed=true`; status agrees |
| Change journal | Final outgoing ID is hydratable; new/content_changed/edited/deleted observed; no gap during connected sequence |
| Restart coverage | Previous cursor accepted after container replacement; `gap=true` explicitly reports restart |
| Persistent export | First page: 52 messages; survives container replacement; next resume: 51 messages, no overlapping IDs; cursor advances |
| Export lifecycle | Short pages remain PAUSED; saved page readable; cancel then delete succeeds; test export removed |
| Managed daemon | Duplicate start, two independent MCP sessions, four task tools, unauthenticated control rejection, stop/restart pass |
| Windows package | Runtime-inclusive ZIP passes STDIO initialize/list/call/lifecycle smoke; stdout JSON-only |
| Automated checks | 799 tests discovered, 797 passed, 2 skipped; JAR, native coverage and version metadata pass |

Live testing caught a provisional outgoing ID in the change journal. The fix
ignores pending messages and records the final `UpdateMessageSendSucceeded`
message; the final candidate was rebuilt and the live new/edit/delete sequence
repeated successfully. One original labeled test message remains; the second,
used for edit/delete checks, was removed.

The export check intentionally covers bounded pages and persistence, not a full
account export. Telegram snapshots, missed offline events, media downloads,
cross-platform packaging and every named desktop MCP client remain unverified.
The daemon test uses isolated local data without a Telegram login; live Telegram
operations use Berloga's shared container. Stage 8 benchmarking remains out of scope.

## Berloga fixes and authentication

The dev batch previously waited for the launcher's secret watchdog before
starting that launcher. Its successful hotfix path also retained errorlevel 1
from a warning. Dev bootstrap now defers secret restoration and successful
hotfix completion explicitly returns zero. The actual `build-dev.bat` reached
Tauri startup, and the router restored both `grok` and `xai` aliases.

xAI refresh/session writes are serialized; expired access tokens are no longer
returned after a failed refresh or reported as usable. Launcher model discovery
also accepts OAuth. All 87 Rust library tests passed. A real CLOUD `grok-4.3`
request returned the requested marker with zero tools; OAuth-only model discovery
returned seven models. Grok CLI credentials were not imported. The historical
`invalid_grant` cause cannot be established retroactively, and an already revoked
refresh token still needs a fresh sign-in.

## Local candidate artifacts

`build/release-1.17.0-rc.1` contains the versioned JAR, runtime-inclusive Windows
ZIP, checksums and a Berloga bundle with a Docker archive, manifest and installer.
The image layers use the installed 1.16.2 runtime pinned by digest, replacing only
the JAR with the tested candidate. The image is local
`berloga/telegram-mcp-tdlib:1.17.0-rc.1`; it is not a published stable GHCR release.

Extract the Berloga bundle, then run `Install-BerlogaCandidate.ps1`. It verifies
the archive and loaded image, backs up `servers.yaml` privately, replaces only
the Telegram image and leaves reconciliation to Connectors → Apply. It never
replaces account credentials or the TDLib volume. To roll back, restore the
previous image entry from the private configuration backup and apply again.
The tested machine already runs this candidate.
