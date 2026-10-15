# Windows host lifetime and CI journal regressions

Date: 2026-10-15. This follow-up addresses two failures reported after the
1.17.1 publication. It contains no credentials or Telegram message content.

## Windows daemon lifetime

Claude Desktop 2.42304 loaded the configured bridge interpreter and script, but
initialization failed because the shared HTTP daemon had stopped after Codex
exited. The previous tests covered stop/start of the daemon itself, not closure
of the Windows Job Object owned by its launching host.

The new offline regression starts a daemon under an isolated kill-on-close Job
Object, then closes that object. The installed 1.17.1 Windows package fails:
the daemon dies. The fixed runnable JAR passes both cases:

- With breakaway permitted, the daemon remains ready after the host job closes.
- With breakaway forbidden, startup fails with an actionable message and starts
  no attached fallback daemon.

Windows startup now calls CreateProcess through JNA 5.18.1 with
CREATE_BREAKAWAY_FROM_JOB, CREATE_NO_WINDOW and CREATE_SUSPENDED. Before resuming
the child, it verifies that no ancestor Job Object was retained: Windows can
successfully leave an inner job while retaining a restrictive outer one. The
packaged-runtime regression caught this case during implementation. A child
that fails verification is terminated before it can open Telegram state.
The launcher keeps the same user identity,
environment and working directory, inherits no handles, and opens its own private
log in the child before Spring startup. No service, elevation, new Telegram login
or session copying is involved. Host policy can still forbid breakaway; an
independent terminal is then required. See the
[Windows flag contract](https://learn.microsoft.com/en-us/windows/win32/procthread/process-creation-flags).

The regression runs in Windows PR/master CI and against the extracted Windows
release archive. Bridge errors now distinguish a failed connection from a safe
HTTP status code and direct the user to daemon status without exposing keys.

## Intermittent ChangeJournal tests

The [PR 33 checks](https://github.com/tolboy/telegram-mcp-tdlib/pull/33/checks)
passed, but the [subsequent master run](https://github.com/tolboy/telegram-mcp-tdlib/actions/runs/38767749101)
failed in `queue overflow is reported after the retained updates`. The
[previous master run](https://github.com/tolboy/telegram-mcp-tdlib/actions/runs/38766954369)
failed in `failed update is reported as a gap and survives the next successful write`.
The Gradle deprecation warning was not the failure.

The overflow test let the writer race the producer and incorrectly assumed a
single overflow interval. It now waits until the writer is blocked on disk,
fills exactly 100 queue slots, then verifies the exact 101 retained message IDs
and one gap marker. The failed-write test now awaits its writer before opening
a different journal instance, which cannot await another instance's queue.
All test instances close after each test, and controlled disk barriers release
and writers stop before removing global mocks. Production journal behavior is
unchanged. CI retains XML and HTML test reports on failure.

## Scope

Companion skill 0.1.1 requires server 1.17.2 and removes stale prerelease setup
instructions. Final native client execution and published release verification
are separate from the offline regression. Marketplace upload remains pending.
