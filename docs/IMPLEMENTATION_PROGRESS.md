# Competitive improvement plan: implementation status

Snapshot: 2026-09-27. This tracks the original eight improvement stages, not
the number of commits. A partial stage still counts as unfinished.

**3 complete, 1 partial, 4 not started: 5 stages still have remaining work.**
Existing project capabilities are not counted as newly completed stages.

| # | Stage | Status | Delivered / remaining |
|---|---|---|---|
| 1 | Honest export/search completeness | Complete for bounded queries | Explicit scope, truncation, partial reasons, query failures, timeout coverage. Resumable full export belongs to stage 6. |
| 2 | Recoverable sends | Complete within the documented journal guarantees | Keys and receipts cover text/replies, files/voice/stickers, forwarding batches and scheduling. Late observed successes persist and reconcile on status/replay. Missing observations remain UNKNOWN; no guessed delivery or automatic resend. See SEND_IDEMPOTENCY.md for restart and file lifetime limits. |
| 3 | Typed tool contracts | Complete | Explicit schemas for all 111 tools, actual handler-result validation, structured dispatch errors and common list-page metadata. Exhaustion and next cursors are never inferred from filtered lists; persistent resume remains stage 6. |
| 4 | Granular permissions | Partial | Existing account/chat restrictions preserved; explicit local-download permission added. Remaining: client/account/chat/action grants and permission management UI. |
| 5 | Task-oriented compact tools | Not started | Inbox snapshots, conversation bundles, changes-since queries and compact response budgets. Existing profiles/context tools are groundwork. |
| 6 | Resumable export and change journal | Not started | Persistent jobs, cursors, resume/cancel/status and incremental changes. |
| 7 | Shared local daemon lifecycle | Not started | Managed start/status/stop, shared client attachment and onboarding. Existing transports/installer are groundwork. |
| 8 | Reproducible competitive benchmark | Not started | Public scenario suite, comparable latency/token/completeness metrics and demonstrated runs. Existing unit tests do not substitute for this. |

Next priority: finish granular permission scoping in bounded
commits; build task-oriented tools on those contracts.

Changes are committed locally. Automated checks use mocked Telegram clients;
live Telegram delivery, cache downloads and cross-client interoperability have
not been verified by these checks. This snapshot is not a release readiness claim.
