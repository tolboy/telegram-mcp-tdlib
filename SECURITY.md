# Security Policy

## Supported versions

Security fixes are applied to the latest released minor version. If a report affects unreleased work, include the current `master` commit as well.

## Reporting a vulnerability

Use this repository's GitHub **Security** tab to submit a private vulnerability report. Do not open a public issue or discussion.

Include a minimal reproduction, affected version, impact, and suggested mitigation if known. Never include a Telegram API hash, bot token, MCP API key, phone number, TDLib database, or private message content. Expect an acknowledgement within 7 days.

## Scope notes

The highest-risk areas are authentication, Streamable HTTP request handling, file path validation, TDLib session data, multi-account routing, tool authorization, and accidental disclosure through logs or the discovery descriptor. The server intentionally exposes Telegram user-account capabilities; operators remain responsible for account permissions, network exposure, and client-side handling of untrusted Telegram content.

For production, use `*_FILE` secret mounts or an orchestrator/OS secret store rather than committing `.env` files. The server rejects symlink secret files and files writable by group or others, and it never writes API hashes, tokens, passwords, codes, or API keys itself. Keep each TDLib account database on encrypted local storage and use a distinct scoped MCP key per remote client wherever possible.

## Automated security checks

When the repository is public, pull requests receive dependency-review and
CodeQL checks, and tagged releases publish an SPDX SBOM, GitHub artifact
attestations, and keyless Sigstore-signed GHCR image digests. These checks
reduce supply-chain risk but do not replace operator responsibility for protecting Telegram
credentials, TDLib databases, MCP keys, or user-provided files.
