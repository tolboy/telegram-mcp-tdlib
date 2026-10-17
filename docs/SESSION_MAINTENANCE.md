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

`td.binlog` is checked before `td.sqlite`, and any held candidate makes the
session `LOCKED`. An open/probe error is `UNAVAILABLE`; in particular, a Windows
handle opened with exclusive sharing (`FileShare.None`) prevents opening the
file and is reported as `UNAVAILABLE`, rather than `LOCKED`. Both refuse clear.

For the interactive default account, use the running server's localhost-only
`POST /auth/logout` endpoint to perform a Telegram logout. For named accounts,
logout must be performed by the running process that owns the corresponding
TDLib client. The offline command's `session logout` subcommand prints these
instructions rather than starting a second TDLib client against the session.

To erase only local session data after a backup:

```powershell
java -jar telegram-mcp-server.jar session clear --account default --yes
```

`clear` requires both `--yes` and an unlocked standard directory such as
`<app-data>/tdlib/default`. It refuses symbolic links, active/unavailable locks,
and custom `TDLIB_DATA_DIR` locations so an operator must handle those paths
deliberately. Clearing local data is not a Telegram logout; use the logout path
first when revoking the server's active session matters.

Clear first renames the directory atomically to a sibling
`.<label>.clearing-<suffix>`, then checks its locks again before deleting it.
Windows may reject the rename while a file is open; POSIX advisory locks do
not prevent renaming, so the second check is required. Unsupported atomic
renames and other staging errors fail closed and report both paths and the
exception type.

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
