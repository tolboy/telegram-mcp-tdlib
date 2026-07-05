# Connector Discovery

This repository now exposes a public descriptor at `/.well-known/mcp-server.json`.

## Why This Exists

This descriptor lets independent, single-purpose MCP servers be packaged together without embedding them into one process.

That means the integration layer should be:

- lightweight,
- stable across repositories,
- host-friendly,
- independent from any specific desktop client or installer.

For that use case, a discovery descriptor is a better fit than building an in-process aggregator or forcing an A2A-style protocol between connectors.

## Contract

The descriptor currently publishes:

- server identity and version,
- transport metadata for the Streamable HTTP MCP endpoint,
- authentication expectations,
- tool inventory,
- launch hints for local-first packaging.

Example shape:

```json
{
  "schemaVersion": 1,
  "id": "telegram-mcp-server",
  "name": "Telegram MCP Server",
  "version": "<embedded-release-version>",
  "transport": {
    "type": "streamable-http",
    "basePath": "/mcp",
    "messagePath": "/mcp"
  },
  "authentication": {
    "required": true,
    "type": "apiKey",
    "acceptedHeaders": [
      "Authorization",
      "X-MCP-API-Key"
    ]
  },
  "capabilities": {
    "toolCount": 110,
    "toolNames": ["get_history", "send_message"]
  },
  "toolProfile": "all",
  "launcherHints": {
    "connectorType": "telegram",
    "deploymentMode": "local-first",
    "defaultBindHost": "127.0.0.1",
    "multiRepoReady": true
  }
}
```

The inventory reflects the active server mode. With `MCP_READ_ONLY=true`, the
descriptor omits mutating and quota-consuming tools rather than advertising
operations that will later be rejected.

## Recommended Platform Pattern

For a local bundle of MCP servers:

1. Ship each connector as an independently versioned service.
2. Require every connector repo to expose the same well-known descriptor shape.
3. Let a host application or installer discover descriptors, render connector cards, and generate client config.
4. Keep cross-cutting policy outside connector repos when possible: secrets, enable/disable state, restart policy, and packaging belong in the host/installer layer.

## Security Notes

- The descriptor is intentionally public and contains no secrets.
- It should describe how to connect, not leak runtime state or credentials.
- Tool names are acceptable to expose in the local-first deployment model; avoid adding sensitive per-session or per-user data here.
- Account labels and per-key scopes are runtime authorization data and are intentionally not included in the public descriptor.
