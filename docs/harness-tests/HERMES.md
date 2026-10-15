Test telegram-mcp-tdlib in this Hermes session using the exact shared acceptance
prompt at:

`C:\Users\anato\kotlin-telegram-mcp\docs\harness-tests\COMMON.md`

Read and execute that file as the test instructions. First record the actual
Hermes version and which profile/session is active, without revealing secrets.
Use native tools exposed to THIS model session for native passes.

`hermes mcp test NAME` is an optional discovery/connection probe, not proof of
a tools/call response reaching the model. Do not mark native content or skill
cases PASS solely because that command exits successfully. Do not run it if it
would create a competing STDIO owner of the existing Telegram session.

Do not switch profiles, alter the user's global MCP configuration or enable a
paid model/provider to force a pass. If there are no Telegram tools in the
current session, report BLOCKED_SETUP and describe the minimal configuration
needed for the prearranged shared read-only daemon. If model/provider access
is unavailable, distinguish BLOCKED_MODEL_ACCESS from a server defect.
