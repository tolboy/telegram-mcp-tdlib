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
the daemon dies. The replacement launcher uses Task Scheduler as the independent
owner of the foreground JVM. Its worker remains active while the daemon runs,
so closing the invoking host's job cannot stop it.

A simple CREATE_BREAKAWAY_FROM_JOB prototype was rejected during testing:
Windows can leave an inner job while retaining a restrictive outer one, and
both the local agent and CI impose outer jobs. See the
[Windows nested-job contract](https://learn.microsoft.com/en-us/windows/win32/procthread/nested-jobs).
The final implementation does not depend on breakaway and adds no JNA dependency.

The task uses the current interactive user's token at Limited run level. It has
no trigger, no password, no elevation and no login/startup automation. It removes
its own registration after the daemon exits. The environment is handed off in
an owner-only bootstrap file under the private daemon directory, consumed and
deleted before JVM startup. Environment values never enter the task definition,
argv or logs. The child inherits no app stdio and opens its private log before
Spring startup. Task registration failure reports an error rather than starting
an attached fallback. Task Scheduler and an interactive user session are required
on Windows; no Windows service is installed.

The regression checks closure of both breakaway-permitted and restrictive host
jobs, authenticated readiness after closure and consumption of the environment
bootstrap. It runs in Windows PR/master CI and against the extracted Windows
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
