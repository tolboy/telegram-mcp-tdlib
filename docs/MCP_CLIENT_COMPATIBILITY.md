# MCP Client Compatibility

The server supports local STDIO and a standards-based Streamable HTTP endpoint:
`POST /mcp`.
It implements the MCP `2025-06-18` lifecycle and requires `Mcp-Session-Id`
after `initialize`. API-key deployments accept `Authorization: Bearer <key>`;
`X-MCP-API-Key` and the configured `MCP_AUTH_HEADER` remain available too.

## Compatibility Matrix

| Client | Connection data | Verification | Status |
|---|---|---|---|
| STDIO | Command `telegram-mcp serve --transport stdio`; secrets supplied through the client's secret/env mechanism | `scripts/mcp-stdio-smoke.ps1` verifies JSON-only stdout, lifecycle and `tools/list` | Automated in CI/release images |
| Streamable HTTP | URL `http(s)://host/mcp`, optional API-key header | `scripts/mcp-streamable-http-smoke.ps1` sends `initialize`, `notifications/initialized`, and `tools/list` | Automated in CI/local checks |
| MCP Inspector | Select **Streamable HTTP**, enter the endpoint and API-key header | Run the smoke script first, then use Inspector's Tools pane to list the same surface | Reproducible manual check |
| Claude Desktop | Add the endpoint and `Authorization` header through the version's MCP/connector UI | Run the smoke script against the same endpoint before enabling the connector | Reproducible manual check |
| Cursor | Add a remote Streamable HTTP MCP server with the endpoint and header | Run the smoke script, then refresh the MCP server and inspect its tool list | Reproducible manual check |
| VS Code | Add a remote HTTP MCP server with the endpoint and header in the version's MCP configuration UI/file | Run the smoke script, then use the MCP server view to list tools | Reproducible manual check |

Client configuration file formats and UI labels evolve independently. The stable
connection data above intentionally avoids pinning an outdated vendor-specific
snippet. Do not place an API key in a committed client configuration file; use
that client's secret/input-variable mechanism.

## MCP Inspector Via Docker Compose

The compose stack ships an optional Inspector service so the complete tool
surface can be browsed and exercised interactively with one command:

```bash
docker compose --profile inspector up
```

Open `http://localhost:6274`, choose **Streamable HTTP**, set the URL to
`http://telegram-mcp:8080/mcp` (the in-network service name), and add the
`Authorization: Bearer <MCP_API_KEY>` header. The Inspector protects its own
UI with a session token printed in the `telegram-mcp-inspector` container log.
Both Inspector ports bind to `127.0.0.1` only, matching the local-first
posture of the rest of the stack.

## Automated Smoke Test

Start the server, then run from PowerShell:

```powershell
./scripts/mcp-streamable-http-smoke.ps1 -Endpoint http://127.0.0.1:8080/mcp -ApiKey $env:MCP_API_KEY

# Optional assertion for a focused surface.
./scripts/mcp-streamable-http-smoke.ps1 -Endpoint http://127.0.0.1:8080/mcp `
  -RequiredTool get_history,send_message -ForbiddenTool create_group
```

The script makes no Telegram tool call. It verifies the full protocol
handshake, session propagation, `tools/list`, and the portable input-schema
profile. It also requires complete behavior annotations on every advertised
tool. The JSON result includes the negotiated protocol version and tool count.
Run it once with `MCP_READ_ONLY=true` and, before enabling production writes,
once with `MCP_READ_ONLY=false`. `-RequiredTool` and `-ForbiddenTool` make it
suitable for profile or policy smoke checks.

Every registered tool also publishes MCP behavior annotations. Read operations
set `readOnlyHint=true`; known destructive operations set
`destructiveHint=true`; Telegram access is marked open-world. Write operations
conservatively set `idempotentHint=false`, so clients should not retry them
automatically after an ambiguous transport failure.

Successful results include both the existing JSON `TextContent` and an MCP
`structuredContent` envelope with `data` and `meta`. Telegram-controlled text
is audience-marked as user data. Invisible formatting and bidi control
characters are represented as visible `\uXXXX` text; instruction-like words
are not filtered or interpreted.

## JSON-Schema Profile

All tools use a JSON object as `inputSchema`, named properties, primitive
types (or a JSON-Schema `type` array for a string-or-number identifier),
arrays, nested objects, enums, and a string `required` list. Clients whose
schema UI cannot represent a string-or-number ID should send the identifier as
a string. The server does not publish `$ref`/`$defs`, schema composition
(`oneOf`, `anyOf`, `allOf`), conditional schemas, `patternProperties`, or
unevaluated-property keywords.
This conservative profile is enforced by
`McpToolSchemaCompatibilityTest` so a future tool cannot silently break a
desktop client's schema parser.

In multi-account mode, account-bound tools gain a string `account` property
whose enum contains only configured account labels; it becomes required only
when more than one account is configured. Read-only mode hides all write and
quota-consuming tools from `tools/list`, including `transcribe_voice_note`,
rather than advertising tools that will reject a call.
