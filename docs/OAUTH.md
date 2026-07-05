# OAuth For Remote HTTP Deployments

API keys remain the default for local-first deployments. OAuth is an optional
resource-server mode for an externally reachable Streamable HTTP endpoint. The
Telegram MCP server validates tokens; it never issues them and does not embed
an authorization server.

```dotenv
MCP_AUTH_MODE=oauth
MCP_OAUTH_ISSUER_URI=https://identity.example.com/
MCP_OAUTH_RESOURCE_URI=https://telegram-mcp.example.com/mcp
# Optional when issuer discovery is unavailable:
MCP_OAUTH_JWK_SET_URI=https://identity.example.com/.well-known/jwks.json
```

The JWT `aud` claim must contain `MCP_OAUTH_RESOURCE_URI`. The server publishes
RFC 9728 metadata at `/.well-known/oauth-protected-resource` and returns its URL
in the `WWW-Authenticate` challenge.

Account restrictions use the `telegram_accounts` claim by default:

```json
{
  "sub": "assistant-client",
  "aud": ["https://telegram-mcp.example.com/mcp"],
  "telegram_accounts": ["work"]
}
```

Set `MCP_OAUTH_ACCOUNTS_CLAIM` or `MCP_OAUTH_PRINCIPAL_CLAIM` when the issuer
uses different claim names. A present account claim is fail-closed: labels not
listed there cannot be selected or returned by `list_accounts`.

OAuth and API-key settings are mutually exclusive. Startup fails if OAuth is
incomplete, uses non-HTTPS metadata, or is mixed with `MCP_API_KEY`,
`MCP_API_KEY_FILE`, or named API-key clients.
