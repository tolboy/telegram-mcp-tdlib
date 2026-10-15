# Prepublication compatibility audit

Assessment date: 2026-10-15. Scope: readiness of the read-only Telegram Inbox
Assistant distribution through Agensi, and a plan for broader harness testing.
This is an initial source/readiness assessment, not a completed full security
audit or a claim of compatibility with every MCP client.

## Current decision

The follow-up implementation and current evidence are recorded in
[1.17.1 readiness](RELEASE_READINESS_1.17.1.md). The assessment below preserves
the initial gaps and wider audit plan. Do not advertise broad client support:
the initial native support list is narrower, and marketplace delivery remains
unverified. A user following the setup guide must obtain the fix in a named
release; local development success alone is insufficient.

## Evidence collected

| Area | Current evidence | Boundary |
| --- | --- | --- |
| Current JVM suite | Full run on Windows: 804 tests discovered, 802 passed, 2 skipped, 0 failures/errors; 168 suites | Mocked/no-credential tests; not 804 live Telegram checks |
| Build and release metadata | bootJar, verifyNativeRuntimeCoverage and verifyReleaseMetadata pass; version 1.17.0-1-g5b712c9-dirty | Native classifier presence is not a fresh live login on every OS |
| Codex native reads | Selected account verified, 20-chat sample, two unread chats, 10 messages per chat; validation failure surfaced | Local Java/Python launcher and an existing named-account session |
| Skill/package | Frontmatter, public JSON/TOML templates, explicit ZIP file inclusion, source/install/archive equality | Agensi delivery and a clean install remain untested |
| Historical live validation | [2026-09-29 report](LIVE_VALIDATION_2026-09-29.md) records Berloga, restart/export/send-recovery and Windows package checks | Historical report on a different candidate; not a fresh run of this audit |
| Other harness | [Desktop tests](harness-tests/RESULTS_2026-10-15.md): Hermes direct HTTP and Claude Desktop via STDIO-to-HTTP bridge pass; Cursor model run blocked by usage limit | The bridge is private local test setup; these versions do not establish the marketplace's full client list |
| Shared local daemon | [Migration and four concurrent HTTP client checks](SHARED_DAEMON_VALIDATION_2026-10-15.md) pass; separate keys and MCP session IDs | The Python HTTP checks are not native Claude/Hermes/Cursor tests; Codex needs reload to use its new HTTP entry |

## Local harness inventory

Initial version/help inventory below predates the authorized desktop runs.
The [desktop results](harness-tests/RESULTS_2026-10-15.md) record subsequent
configuration changes, actual model runs and exact running-app versions.

| Harness | Local version/surface | Practical next test | Important limitation |
| --- | --- | --- | --- |
| Codex | Current desktop chat, native Telegram calls already verified | Repeat on the exact released runtime and public skill ZIP | Current success used a custom local launcher |
| Claude Code | 2.1.283 | Isolated `--mcp-config` / `--strict-mcp-config` plus a restricted `-p --output-format stream-json` task | Health/list commands are not a result-parsing test |
| Hermes | 0.21.6+309.ge0550c9 | Dedicated test profile; `hermes mcp test NAME`, followed by an actual read-only agent task | Installed probe implementation connects/lists/shuts down; it does not call tools |
| Cursor | Initial PATH inventory: editor 2.4.22; opened user-local executable: 3.24.18 | MCP panel plus a native read-only tool call in editor chat | The opened Agent run hit its usage limit; `agent` on PATH currently resolves to Grok, not Cursor |
| Antigravity | AgY CLI 1.3.4; app 2.4.3; IDE distribution 2.5.5 | Native/headless CLI transcript and individual app/IDE checks for claimed surfaces | `mcp list` is not proof of a successful call; one surface does not certify the others |
| Orca | 1.4.224; runtime not running during inventory | Run the chosen underlying agent through its managed launch path | No separate native MCP test/config API found in CLI help; distinguish agent compatibility from Orca launch/environment checks |

Claude's [documented STDIO shutdown behavior](https://code.claude.com/docs/en/mcp#how-claude-code-stops-a-stdio-server)
is version/platform dependent. The installed Windows version predates the
documented 2.1.296 lifecycle change. Include abrupt process-tree termination and
session recovery, not only the server's graceful EOF path. This is a test risk,
not a claim that data loss was observed. Do not update installed clients merely
to hide the older-version behavior: first record the tested version and outcome.

Current full-suite command (JAVA_HOME points to local Java 25):

```powershell
./gradlew.bat test bootJar verifyNativeRuntimeCoverage verifyReleaseMetadata '-Pktm.buildDir=build-codex-telegram-test' --console=plain
```

Telegram/authentication environment values were removed from the test process.
Tests ran with the repository's test profile and did not use the Telegram session.

## Concrete gaps

1. **Release prerequisite:** the fixed runtime must be released and SETUP/listing
   must name its minimum compatible version. A working developer JAR is not the
   artifact a marketplace user installs.
2. **Transport smoke coverage:** `scripts/mcp-stdio-smoke.ps1` sends initialize
   and tools/list, then checks EOF shutdown. The HTTP smoke likewise checks
   discovery. Neither invokes tools/call. This explains why a valid discovery
   surface could coexist with the result-parsing failure seen in Codex.
3. **Independent response parsing:** handler result/schema tests use the Java
   stack. Add independent client parsers for success, structured errors and
   text/structured-content combinations; schema presence alone is insufficient.
4. **Advisory annotations:** `ToolSupport.kt` currently marks text audience as
   user, while also supplying structuredContent. That is a host-interpretation
   risk for text-only clients, not a reproduced defect. Check that the model
   actually receives useful content. Output schemas also use valid `anyOf`;
   the existing portable-profile test checks input schemas, not independent
   host handling of output schemas.
5. **Onboarding and delivery:** repeat the public installation flow from an
   empty application-data directory, on a runtime bundle without developer PATH
   dependencies. Test first authorization, session reuse and skill ZIP install.
   Keep it separate from a live production session.

## Proposed test layers

| Layer | Method | Purpose |
| --- | --- | --- |
| Protocol | Pinned MCP Inspector CLI and independent Python/TypeScript SDK clients over STDIO and HTTP | Negotiation, schemas, actual success/error results, lifecycle |
| Parser fixtures | One synthetic MCP fixture server with deterministic sanitized response variants | Run the same Unicode, empty, truncated, large-ID, annotations and error cases through each host without Telegram or secrets |
| Real host | Native calls from each installed harness and a bounded skill request | Verify host parsing, tool availability to the model, account selection and usable answers |
| Account integration | One controlled test account with a single session owner | Cold history, pagination, denied chats, restart/reconnect and read-only behavior |
| Distribution | Extract the exact release bundle and skill ZIP; follow only public docs | Catch missing runtime/native dependencies, bad paths, config shape and release/version drift |
| Marketplace | Review a free Agensi submission and test the delivered skill | Verify dependency presentation, links, install and final workflow |

The official [MCP Inspector](https://modelcontextprotocol.io/docs/tools/inspector)
has a scriptable CLI. It is a protocol client, not a substitute for the actual
hosts. Pin its version and use that version's documentation. The official
[conformance project](https://github.com/modelcontextprotocol/conformance)
is useful, but full fixture-oriented suites are not a drop-in pass/fail test
for an arbitrary production Telegram tool catalog. Run applicable scenarios
for the advertised protocol version and use an isolated test-only fixture
for scenarios requiring named reference tools.

OpenAI's [testing guidance](https://developers.openai.com/plugins/deploy/connect-chatgpt)
also separates direct server capability testing from the complete installed
skill/plugin workflow. It does not certify this server or the Agensi package.

## Native-client scenario

Prepared [copy/paste prompts](harness-tests/README.md) for Claude, Hermes and
Cursor share one bounded scenario and an anonymized report format. They require
native tool execution and distinguish blocked setup/model access from server
failures. They do not establish broad marketplace compatibility by themselves.

For each exact harness version, record the release version/hash, OS, transport,
configuration and deidentified trace. Verify:

- discovery, selected get_me and a successful bounded inbox read;
- conversation pagination, partial/empty results and invalid input;
- actual content available to the model, including text fallback;
- tool and skill selection for direct, indirect and unsupported requests;
- no writes/downloads on the advertised read-only surface;
- disconnect, restart/reconnect and session-lock behavior;
- embedded Telegram instructions treated as data in controlled fixtures.

For several clients using one account, use one shared daemon. Do not start
parallel STDIO owners or copy an active TDLib database. Compare both transports
where a client claims support, and separate protocol results from model quality.
Changing a user's saved client configuration is not necessary for parser-fixture
tests; use isolated per-test configuration where supported.

## Minimum publication gates

1. Full tests/build and applicable independent protocol/result checks pass on
   the exact release candidate.
2. The candidate runtime and skill ZIP pass the public installation path.
3. Keep the initial native support list to Codex, Hermes Desktop and Claude
   Desktop Chat via the documented tools bridge. Claude Code, VS Code / Copilot,
   Orca and Antigravity remain individually unverified. Cursor was excluded from
   the required native gate at the user's request after its host/model access
   failure; keep its example configuration without claiming a native pass.
4. Known critical/high-impact findings are resolved; skipped tests and
   remaining limits are explained rather than counted as passes.
5. A release containing the fix is downloadable, SETUP names the version,
   and listing language matches the tested support matrix.

These gates reduce preventable failures; they cannot guarantee zero negative
reviews or compatibility with future client updates.

## Python reference comparison

Current source snapshots, inspected without executing competitors or supplying
credentials. Stars are discovery signals, not compatibility certificates.

| Reference | Pinned source | Useful comparison | Evidence limit |
| --- | --- | --- | --- |
| chigwell/telegram-mcp | [6b925e5](https://github.com/chigwell/telegram-mcp/tree/6b925e5ed86435b4285f063a561f9ea284b3e36e) | Dedicated null-default, negotiation, annotations, error and session tests | Source/CI inspection, not independent execution; its CI does not prove every host |
| dryeab/mcp-telegram | [653a1ed](https://github.com/dryeab/mcp-telegram/tree/653a1ed43c4927b28b4060e6759d3c4009d5ea00) | CLI login/logout/clear-session and path/session-owner ergonomics | No tests directory found in inspected Git tree; publish workflow is not a compatibility suite |
| sparfenyuk/mcp-telegram | [c524633](https://github.com/sparfenyuk/mcp-telegram/tree/c524633eec4e37797a255efaa702af091c9d709a) | Minimal text-result baseline and separate sign-in | No tests/CI workflows found in inspected Git tree; simpler result paths do not cover our structured results |

A concrete regression worth adopting comes from chigwell's
[null-default tests](https://github.com/chigwell/telegram-mcp/blob/6b925e5ed86435b4285f063a561f9ea284b3e36e/tests/test_tool_schema_null_defaults.py):
some harnesses send the advertised optional `default: null` even when the schema
allows only a string. Test omitted/null/valid/wrong-type arguments separately.
Do not copy that policy blindly; compare the actual schema and intended input
contract. Our centralized write hiding, account scoping and structured result
envelopes are useful properties to preserve while testing interoperability.

For reproducible tooling evaluation, the researched releases were MCP Inspector
2.11.0 and Python SDK v2.3.0. The inspected conformance source was
[c37eec8](https://github.com/modelcontextprotocol/conformance/tree/c37eec888e1c6ff140af79987a40008548b7cc5f).
Those are research snapshots, not newly installed dependencies or executed gates.
