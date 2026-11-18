# Session Doctor And Maintenance

The packaged JAR has an offline `session` command. It reads local paths and
environment variable names only: it does not start TDLib, contact Telegram, or
print API hashes, tokens, phone numbers, passwords, auth codes, or proxy
secrets.

```powershell
java -jar telegram-mcp-server.jar session doctor
```

The doctor reports the OS application-data directory, configured account
labels, each account's TDLib database directory, and whether a TDLib lock file
appears available. File-lock owner PIDs are not exposed portably by Windows,
macOS, and Linux; `lock_owner=not available portably` is deliberate. A `LOCKED`
or `UNAVAILABLE` result means stop the owning server/process before session
maintenance.

Each account also shows `account_check=`, the last expected-account check the
server recorded (`tdlib.auth.expected-user-id` / `expected-phone-number`, or
`TDLIB_EXPECTED_USER_ID` / `TDLIB_EXPECTED_PHONE_NUMBER`). It is one of
`NOT_RUN` (no record yet), `NOT_CONFIGURED`, `MATCH`, `MISMATCH`,
`UNVERIFIED` or `ARTIFACTS_REFUSED`, with the expected and actual user IDs and
masked phone numbers. `ARTIFACTS_REFUSED` means Telegram confirmed the identity
and the server refused the local artifact store.
The record is `telegram-mcp-account-check.json` in the account's database
directory, written each time the session becomes ready, and removed by
`session clear` together with the session.

When an expected account is configured, account-scoped MCP calls require a
verified session. This includes saved change events, export pages and send
receipts. Resource subscriptions and update notifications use the same
readiness check. Account labels and tool definitions remain discoverable
within the caller's normal scope during verification.

### Bind or migrate local MCP artifacts

With expected-account verification enabled, the first accepted login binds the
account label's change journal, export jobs and send receipts to the Telegram ID
returned by TDLib and the selected network (production or test DC). The
server atomically writes `account-artifact-owners/<label>.owner` under
`TELEGRAM_MCP_DATA_DIR`. Later logins for the label require the same verified
identity and network. Use a different label with its own TDLib directory or
a separate application data directory for another owner.

Existing artifacts without an ownership record require a one-time operator
assertion. Legacy export directories are opaque hashes, so any saved export
in the data directory can trigger this requirement for a label without a record.
Stop all processes using that label's application data and back up the directory.
If the artifacts for the label belong to the account, set
`TDLIB_LEGACY_ARTIFACT_OWNER_USER_ID` to that account's numeric Telegram ID
and start with expected-account verification enabled. For a named account use
`TELEGRAM_ACCOUNTS_<LABEL>_AUTH_LEGACY_ARTIFACT_OWNER_USER_ID`. The server checks
the assertion against the live TDLib user ID before creating the record.
Once `session doctor` shows `MATCH` and the ownership record is present,
remove the one-time assertion from configuration and keep the expected-account
check. Cursors, export IDs, delivery receipts and idempotency keys retain their
original values. A restarted journal still emits its normal coverage marker.

If ownership of the old data is uncertain, keep it archived and start with a
separate `TELEGRAM_MCP_DATA_DIR`. Reconcile outstanding deliveries before
retrying sends in the new directory. Keep ownership records with the artifacts
in backups. `session clear` for the TDLib database leaves this binding in place.
Older server versions ignore these records. When rolling back to such a
version, use a dedicated data directory for the original owner.

`td.binlog` is checked before `td.sqlite`, and any held candidate makes the
session `LOCKED`. An open/probe error is `UNAVAILABLE`; in particular, a Windows
handle opened with exclusive sharing (`FileShare.None`) prevents opening the
file and is reported as `UNAVAILABLE`, rather than `LOCKED`. Both refuse clear.

For the interactive default account, use the running server's localhost-only
`POST /auth/logout` endpoint to perform a Telegram logout. For named accounts,
logout must be performed by the running process that owns the corresponding
TDLib client. The offline command's `session logout` subcommand prints these
instructions rather than starting a second TDLib client against the session.

To clear local session data and keep a recovery copy:

```powershell
java -jar telegram-mcp-server.jar session clear --account default --yes
```

The default keeps an unencrypted backup containing Telegram session credentials
under `<app-data>/tdlib-backups/<account>/`. The command prints the backup path.
Backups have no automatic expiry. To clear the session without creating a copy:

```powershell
java -jar telegram-mcp-server.jar session clear --account default --yes --no-backup
```

`--no-backup` skips the new copy. Earlier backups remain for the operator to
review and remove. For a compromised session, revoke the Telegram session
before clearing the local files and check the backup tree for old copies.

`clear` requires both `--yes` and an unlocked standard directory such as
`<app-data>/tdlib/default`. It refuses symbolic links, junctions, active/unavailable locks,
and custom `TDLIB_DATA_DIR` locations so an operator must handle those paths
deliberately. Clearing local data is not a Telegram logout; use the logout path
first when revoking the server's active session matters.

Clear first renames the directory atomically to a sibling
`.<label>.clearing-<suffix>`, then checks its locks again before deleting it.
Windows may reject the rename while a file is open; POSIX advisory locks do
not prevent renaming, so the second check is required. Unsupported atomic
renames and other staging errors fail closed and report both paths and the
exception type. On Windows, a transient access denial while publishing the
completed backup is retried briefly. If publication still fails, clear attempts
to restore the staged session and preserves the session data.

If the staged lock is held or unavailable, clear attempts to restore the
original path without replacing a newly created file or directory. A failed
restore deletes nothing and reports where the staged data remains. A failed
deletion can leave partially removed staged data. `session doctor` lists these
leftovers as `staged_directory` even when the canonical account directory is
absent, and clear refuses further attempts until they are recovered. Stop all
owners, back up and inspect both paths, then manually recover or remove the
staged data. Do not overwrite a new session at the canonical path.

Keep the server and any automatic restarter stopped throughout maintenance.
The probes do not form a critical section with TDLib startup: a new process
can recreate the canonical directory after staging, and a successful clear
only removes the staged session.
