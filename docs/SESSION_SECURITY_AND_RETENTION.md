# Session security and retention

Updated: 2026-11-08. This is the threat model for TDLib session files and the
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
- On Windows, dangerous bits are evaluated in existing ACE order for each
  principal: a later DENY does not revoke an earlier ALLOW. Inherited entries
  already on the object count; inherit-only entries do not. Ancestor findings
  are logged. The session directory itself rejects dangerous replace grants
  to every broad group, including BUILTIN\Users; normal READ/EXECUTE grants are
  unaffected. The ACL is not rewritten. This is a conservative per-principal
  check, not a full AccessCheck simulation of overlapping group memberships.
  The ordering follows [Microsoft's DACL access-check contract](https://learn.microsoft.com/en-us/windows/win32/secauthz/how-dacls-control-access-to-an-object).

### Upgrade check for existing installations

On Linux and macOS, review the session path before upgrading. A directory such
as `~/.local/share` created with `umask 002` can be `775`, even when the TDLib
directory itself is `700`. The startup error names the ancestor that needs
attention. For configured secret files the check covers the file and its
immediate parent.

If the directory belongs to the server operator and does not need to be shared,
remove group and other write permissions on the reported directory with
`chmod go-w <directory>`. When a directory must remain shared, stop the server
and move the affected state or secrets to a private directory under a safe
parent. Set `TELEGRAM_MCP_DATA_DIR` for the application data root and update any
absolute TDLib storage or `*_FILE` paths that point to moved data. Preserve
send receipts along with session data when moving the application directory.

## Receipt retention

By default, `session clear` rechecks the staged session lock before copying any bytes. It
copies into a fresh private `.pending-<uuid>/data` directory, flushes each file,
then atomically publishes `tdlib-backups/<account>/<uuid>/data` before deleting
the staged source. Backup directories are owner-only on POSIX and have an
owner-only inheritable Windows ACL; backup ancestors are also audited. Links,
junctions and nonregular source files are refused. A failed copy removes the
partial backup and attempts to restore the source without replacing a new
canonical session. If restoration fails, the error names the recoverable staged
directory rather than claiming that restoration succeeded. A later deletion
failure names both the staged directory and complete backup.

Use `session clear --account <label> --yes --no-backup` to skip the new copy
while keeping the same path and lock checks. Inspect earlier backups separately.
See [session maintenance](SESSION_MAINTENANCE.md).

Backups are not encrypted and have no automatic expiry. Protect the application
data directory and include backups in the operator's encrypted retention policy.
Clearing a custom TDLib directory is still refused. These checks do not provide
an atomic descriptor-based defence against hostile concurrent ancestor changes.
Standard session storage that resolves through a symlink or junction is refused
as well; renaming a link must never authorize removing its external target.
The entire `tdlib-backups` tree is excluded from upload and download paths even
when the operator grants a broader parent as an allowed file root.

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
