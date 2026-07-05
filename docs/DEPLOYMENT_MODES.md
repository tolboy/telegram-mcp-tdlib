# Deployment Modes

This project currently supports two operational modes that matter for architecture and security decisions.

## 1. Local Boxed Deployment

Use this mode for any Windows/macOS/Linux installer or local service bundle that combines:

- the Telegram MCP server,
- optional connector services,
- one or more local small language models,
- optional vendor API integrations.

Recommended defaults:

- Bind connector services to `127.0.0.1` only.
- Treat Docker networking as an internal process boundary, not an Internet boundary.
- Prefer OS-native installer/service management over exposing raw container ports.
- Keep secrets in OS keychain / secret store where possible.
- Use mounted `*_FILE` secrets for container deployments; never place tokens or API hashes in an image layer.
- Let the server choose its platform app-data directory by default, or set an absolute encrypted volume path for every TDLib account.

In this mode, an extra reverse proxy is optional rather than required. HTTPS inside the same machine usually adds complexity without improving the real threat model.

## 2. Remote / VPS Deployment

Use this mode only when the MCP server must be reachable from outside the host.

Recommended defaults:

- Publish the app behind a dedicated reverse proxy or ingress.
- Terminate TLS at the proxy.
- Restrict direct container exposure; do not publish the app port publicly without TLS.
- Add IP allow-lists, rate limits, request size limits, and centralized access logs at the proxy layer.
- Assign a distinct scoped MCP key to each remote client and restrict it to the account labels it needs.

For this repository, a reverse proxy matters mostly in remote deployments. It is not a prerequisite for a local installation.

## Separation Of Responsibilities

Any multi-connector deployment benefits from a strict separation between:

- connector runtime services,
- shared security and audit policy,
- LLM routing/orchestration,
- packaging / installer concerns.

The practical rule is simple:

- local appliance: localhost-first, no mandatory proxy,
- remote hosting: proxy-first, TLS-first.
