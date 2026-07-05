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
