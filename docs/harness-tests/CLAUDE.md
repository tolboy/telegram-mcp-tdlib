Test telegram-mcp-tdlib in this Claude session using the exact shared acceptance
prompt at:

`C:\Users\anato\kotlin-telegram-mcp\docs\harness-tests\COMMON.md`

Read and execute that file as the test instructions. Report the actual Claude
surface/version; do not conflate Claude Code, Claude Desktop and Work mode.
Use only native Telegram MCP tools available in THIS session for native passes.

If local files are unavailable, stop with BLOCKED_SETUP and ask the operator
to paste COMMON.md; do not invent its contents. If this Claude surface cannot
connect to local MCP, record that limitation rather than testing the server
through shell HTTP calls and labeling it native.

No Work-mode subscription is required by the test design. This does not remove
the chosen Claude surface's own authentication, billing or model-access
requirements. If model access is unavailable, the operator can still run
independent protocol tests outside Claude; they do not count as a Claude model
or skill pass. Do not enable paid access or change the subscription.

Where a separately authorized isolated CLI run is used, consult the installed
Claude Code help for --mcp-config and --strict-mcp-config. Do not change global
configuration or start a second TDLib process. Do not test shutdown by ending
this working session. Record restart and shutdown as NOT_TESTED in this run.
