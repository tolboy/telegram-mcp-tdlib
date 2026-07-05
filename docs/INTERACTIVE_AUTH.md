# Interactive Telegram Authentication

The preferred local first-run flow is the explicit CLI wizard:

```bash
telegram-mcp auth --account default --method qr
```

It starts a temporary nonce-protected loopback page, renders QR data locally,
and exits after TDLib persists the session. The server also exposes the
underlying `/auth/**` API for trusted launcher integrations.
Credentials are held in memory; TDLib persists only its normal account session
under the configured data directory.

The standalone CLI accepts a named account label. The long-running server's
`/auth/**` API remains limited to the default single-account setup so it cannot
replace an already running isolated account registry.

## Network boundary

- Requests arriving directly from IPv4 or IPv6 loopback can use `/auth/**`
  without an MCP API key.
- Requests arriving through Docker, a reverse proxy, or a private LAN must send
  `Authorization: Bearer <MCP_API_KEY>` (or another configured key header).
- Do not expose the auth endpoints to the public internet. Terminate TLS before
  any non-local HTTP hop.

This deliberately does not trust all RFC1918 addresses: another container or
LAN device is not the same security principal as the local process.

## QR login

Submit the Telegram application credentials without a phone number:

```powershell
$baseUrl = 'http://127.0.0.1:8080'
$headers = @{ Authorization = "Bearer $env:MCP_API_KEY" }
$credentials = @{
  apiId = [int]$env:TDLIB_API_ID
  apiHash = $env:TDLIB_API_HASH
} | ConvertTo-Json

Invoke-RestMethod -Method Post -Uri "$baseUrl/auth/credentials" `
  -Headers $headers -ContentType 'application/json' -Body $credentials
Invoke-RestMethod -Method Post -Uri "$baseUrl/auth/request-qr" -Headers $headers
Invoke-RestMethod -Uri "$baseUrl/auth/state" -Headers $headers
```

Poll `GET /auth/state` until it returns `waitingQr`. The `qrLink` value is a
sensitive, short-lived `tg://` login link. Render it with a trusted local QR
tool and scan it from Telegram on another device, or open it directly with a
locally installed Telegram client. Never paste the link into a public QR
website, log, issue, or chat.

TDLib refreshes expired QR links through the same state endpoint. Continue
polling until the state becomes `ready`.

## Phone and code login

Include `phoneNumber` when submitting credentials:

```json
{
  "apiId": 12345,
  "apiHash": "your-api-hash",
  "phoneNumber": "+1234567890"
}
```

When `GET /auth/state` returns `waitingCode`, submit:

```json
POST /auth/submit-code
{ "code": "12345" }
```

If it returns `waitingPassword`, submit the Telegram 2FA password:

```json
POST /auth/submit-password
{ "password": "your-2fa-password" }
```

Do not save the code or password in shell history. Prefer an interactive local
client that reads them as secrets.

## Logout

`POST /auth/logout` asks Telegram to terminate the current default-account
session. For offline inspection or local-state clearing, use the session
maintenance CLI documented in [SESSION_MAINTENANCE.md](SESSION_MAINTENANCE.md).
