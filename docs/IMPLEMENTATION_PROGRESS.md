# Competitive improvement plan: implementation status

Snapshot: 2026-09-28. This tracks the original eight improvement stages, not
the number of commits. A partial stage still counts as unfinished.

**7 complete within documented guarantees, 0 partial, 1 not started: stage 8 remains.**
Existing project capabilities are not counted as newly completed stages.

| # | Stage | Status | Delivered / remaining |
|---|---|---|---|
| 1 | Honest export/search completeness | Complete for bounded queries | Explicit scope, truncation, partial reasons, query failures, timeout coverage. Persistent export is delivered separately in stage 6. |
| 2 | Recoverable sends | Complete within the documented journal guarantees | Keys and receipts cover text/replies, files/voice/stickers, forwarding batches and scheduling. Late observed successes persist and reconcile on status/replay. Missing observations remain UNKNOWN; no guessed delivery or automatic resend. See SEND_IDEMPOTENCY.md for restart and file lifetime limits. |
| 3 | Typed tool contracts | Complete | Explicit schemas for all 115 tools, actual handler-result validation, structured dispatch errors and common list-page metadata. Exhaustion is never inferred from filtered lists; persistent resume is delivered in stage 6. |
| 4 | Granular permissions | Complete | Reviewed JSON grants separate client/account/chat access and read/download/mutate/quota/policy actions. Default deny when enabled; existing restrictions intersect. Offline editor imports, reviews and exports rule files; applying changes requires operator-controlled replacement and restart. Account and permission context propagate to parallel research workers. |
| 5 | Task-oriented compact tools | Complete | `inbox_snapshot`, `conversation_bundle`, `changes_since`; bounded normalized JSON character budgets, explicit truncation/scope, chat filtering, typed schemas and profile integration. See TASKS_AND_EXPORTS.md. |
| 6 | Resumable export and change journal | Complete within documented local-observation guarantees | `export_job` start/resume/status/page/cancel/delete; pull-driven durable pages and cursors, account/client ownership and current permission checks. Persistent bounded TDLib new/edit/delete journal with restart/reconnect/retention gaps. No claim of an atomic Telegram snapshot or recovery of unobserved offline events. |
| 7 | Shared local daemon lifecycle | Complete for managed local HTTP | `daemon start/status/stop/attach`, process lease and instance verification, private persistent key, loopback listener, generated client configuration and onboarding. Windows process/transport smoke passed with two independent MCP sessions. Named client applications and other operating systems remain unverified. See LOCAL_DAEMON.md. |
| 8 | Reproducible competitive benchmark | Not started | Public scenario suite, comparable latency/token/completeness metrics and demonstrated runs. Existing unit tests do not substitute for this. |

Next priority: stage 8, reproducible competitive benchmark. It was outside this
request and remains not started; the daemon smoke is not a competitive benchmark.

Stages 5–7 are recorded in separate local commits for durable storage, MCP task
tools, and managed daemon lifecycle.
Validation: 798 tests discovered, 796 passed, 2 skipped, zero failures. Boot JAR assembly, native-runtime
coverage and release metadata checks passed, as did `scripts/Test-ManagedDaemon.ps1`.
Tests cover restart/resume, checkpoint-write failure, terminal cancellation,
account/client isolation, permission revocation, normalized budgets, actual output
schemas, journal gaps/retention, PID reuse rejection and control endpoint binding.

Telegram operations use mocks. The live smoke checks the real local JVM/HTTP/MCP
lifecycle with no Telegram credentials or Telegram calls. Live Telegram history,
update delivery, media downloads and integration inside named client applications
have not been verified. This snapshot is not a release readiness claim.
