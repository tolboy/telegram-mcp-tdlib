# Session security and retention

Date: 2026-11-07. This is the threat model for TDLib session files and the
send-receipt journal. It does not claim that the session database is encrypted.

## What TDLight does not provide

The builder used here is `SimpleTelegramClient` from TDLight
`3.5.9+td.1.8.68`. That builder has no database-encryption key. TDLib itself
can encrypt a database; this server does not turn that on. A directory that
is owner-only is access control, not encryption. Copies, backups, disk images,
and hibernation files contain the same account secrets as `td.binlog`.

## Threat model

| Asset | Who should read it | What goes wrong if it leaks or changes |
|---|---|---|
| Session directory (`td.binlog` and its siblings) | The OS account that runs the server | Anyone with the files can use the Telegram account |
| Download cache | Same account | Media the account has fetched |
| Send-receipt journal | Same account | Deleting a SENT or UNKNOWN receipt makes the same idempotency key look unused, so a retry can send again |
| Upload snapshots | Same account, and only until the send is settled | A snapshot is a private copy of a file the operator chose to send |

Backups are in scope. Volume encryption or an encrypted backup of the whole
data directory is the control this deployment has. The server will not pretend
a chmod or an ACL is a substitute.

## Ancestor access

`LocalAccessChecks.prepareOwnerDirectory` creates the directory and, on POSIX,
sets it owner-only. It then walks ancestors:

- On POSIX, a group- or world-writable ancestor without the sticky bit stops
  startup. That ancestor can rename the private directory away and replace it.
- On Windows, the walk logs shared principals that can delete or rewrite an
  ancestor. It does not fail startup: a normal profile grants broad read, and
  refusing those grants would lock the operator out. The directory itself is
  not rewritten, for the same reason as before.

## Receipt retention

`SendOperationJournal` deletes a receipt only when its status is `REJECTED`,
which means Telegram did not execute the send. `SENT`, `SCHEDULED`, and
`UNKNOWN` records stay. There is no retention job that removes them. A later
cleanup that deletes those files would remove the protection against a
duplicate send and has to be a separate, explicit design.

Upload snapshots are different. They are bytes, not the idempotency record.
Each uses a private UUID directory while retaining the original filename.
Success and known pre-dispatch rejection attempt immediate cleanup; failures
during cleanup do not change a confirmed send result. Ambiguous callback errors,
including partial delivery, timeout, and local cancellation, retain copies
conservatively for 24 hours. Expiry runs on every subsequent upload and skips
copies with an active OS lease, including copies used by another process.
The cache has shared byte/count limits; an idle daemon does not run a background
expiry timer. Deleting a snapshot does not authorize another send; the receipt
journal does. The protected snapshot directory is inaccessible to file tools.

Source files are opened with NOFOLLOW_LINKS and checked around copying. POSIX
hard links are rejected when `unix:nlink` is available. Windows Java NIO does
not provide that check; repeated parent replacement or hostile in-place writes
with preserved metadata are not covered by an atomic descriptor-identity claim.
Use allowed roots controlled by the operator rather than a shared writable tree.

## Out of scope

A custom TDLib client whose only purpose is to pass an encryption key is not
part of this server. Adding one would be its own compatibility change, gated
like any other TDLib upgrade.
