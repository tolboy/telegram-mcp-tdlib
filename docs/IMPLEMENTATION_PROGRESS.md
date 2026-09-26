# Competitive improvement plan: implementation status

Snapshot: 2026-09-26. This tracks the original eight improvement stages, not
the number of commits. A partial stage still counts as unfinished.

**1 complete, 3 partial, 4 not started: 7 stages still have remaining work.**
Existing project capabilities are not counted as newly completed stages.

| # | Stage | Status | Delivered / remaining |
|---|---|---|---|
| 1 | Honest export/search completeness | Complete for bounded queries | Explicit scope, truncation, partial reasons, query failures, timeout coverage. Resumable full export belongs to stage 6. |
| 2 | Recoverable sends | Partial | Durable account-scoped keys, receipts, unknown outcomes and status lookup for text/replies delivered. Extend to media/forwarding/scheduling and reconcile late delivery events. |
| 3 | Typed tool contracts | Partial | Structured errors and strict output schemas for 5 of 111 tools delivered. Extend schemas to the remaining 106, normalize middleware errors and pagination contracts. |
| 4 | Granular permissions | Partial | Existing account/chat restrictions preserved; explicit local-download permission added. Remaining: client/account/chat/action grants and permission management UI. |
| 5 | Task-oriented compact tools | Not started | Inbox snapshots, conversation bundles, changes-since queries and compact response budgets. Existing profiles/context tools are groundwork. |
| 6 | Resumable export and change journal | Not started | Persistent jobs, cursors, resume/cancel/status and incremental changes. |
| 7 | Shared local daemon lifecycle | Not started | Managed start/status/stop, shared client attachment and onboarding. Existing transports/installer are groundwork. |
| 8 | Reproducible competitive benchmark | Not started | Public scenario suite, comparable latency/token/completeness metrics and demonstrated runs. Existing unit tests do not substitute for this. |

Next priority: finish send recovery for late delivery outcomes before expanding
idempotency to additional write tools. Continue permission scoping and typed
contracts in bounded commits; build task-oriented tools on those contracts.

Changes are committed locally. Automated checks use mocked Telegram clients;
live Telegram delivery, cache downloads and cross-client interoperability have
not been verified by these checks. This snapshot is not a release readiness claim.
