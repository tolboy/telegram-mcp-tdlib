param(
    [Parameter(Mandatory = $true)][string]$Jar,
    [int]$Port = 18089
)
$ErrorActionPreference = 'Stop'
$Jar = (Resolve-Path -LiteralPath $Jar).Path
$smokeRoot = Join-Path ([IO.Path]::GetTempPath()) ('telegram-mcp-daemon-smoke-' + [guid]::NewGuid())
$null = New-Item -ItemType Directory -Path $smokeRoot
$savedEnvironment = @{}
Get-ChildItem Env: | Where-Object Name -Match '^(TDLIB_|TELEGRAM_|MCP_|SPRING_|SERVER_|AUTH_)' | ForEach-Object {
    $savedEnvironment[$_.Name] = $_.Value
    [Environment]::SetEnvironmentVariable($_.Name, $null, 'Process')
}
$env:TELEGRAM_MCP_DATA_DIR = $smokeRoot
Push-Location $smokeRoot

function Invoke-Daemon([string[]]$CliArgs) {
    $result = & java -jar $Jar daemon @CliArgs
    if ($LASTEXITCODE -ne 0) { throw "Daemon command failed: $($CliArgs -join ' ')" }
    return ($result -join "`n")
}

try {
    $started = Invoke-Daemon @('start', '--port', "$Port")
    if ($started -notmatch '^READY') { throw "Daemon not ready: $started" }
    $first = Get-Content -LiteralPath (Join-Path $smokeRoot 'daemon/state.json') -Raw | ConvertFrom-Json
    $null = Invoke-Daemon @('start', '--port', "$Port")
    $second = Get-Content -LiteralPath (Join-Path $smokeRoot 'daemon/state.json') -Raw | ConvertFrom-Json
    if ($first.pid -ne $second.pid) { throw 'Repeated start created another process' }

    # Capture credentials locally; never write the generated key to test output.
    $config = (Invoke-Daemon @('attach', '--client', 'cursor')) | ConvertFrom-Json
    $entry = $config.mcpServers.telegram
    $headers = @{ Authorization = $entry.headers.Authorization; Accept = 'application/json, text/event-stream' }
    $sessions = @()
    foreach ($name in @('smoke-a', 'smoke-b')) {
        $initialize = @{ jsonrpc = '2.0'; id = 1; method = 'initialize'; params = @{
            protocolVersion = '2025-03-26'; capabilities = @{}; clientInfo = @{ name = $name; version = '1' }
        }} | ConvertTo-Json -Depth 5 -Compress
        $response = Invoke-WebRequest -Uri $entry.url -Method Post -Headers $headers -ContentType application/json -Body $initialize
        $session = [string]$response.Headers['Mcp-Session-Id']
        if (!$session -or $sessions -contains $session) { throw 'Expected independent MCP session IDs' }
        $sessions += $session
    }
    foreach ($session in $sessions) {
        $headers['Mcp-Session-Id'] = $session
        $headers['MCP-Protocol-Version'] = '2025-03-26'
        $null = Invoke-WebRequest -Uri $entry.url -Method Post -Headers $headers -ContentType application/json -Body '{"jsonrpc":"2.0","method":"notifications/initialized"}'
        $listed = Invoke-WebRequest -Uri $entry.url -Method Post -Headers $headers -ContentType application/json -Body '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'
        foreach ($tool in @('inbox_snapshot', 'conversation_bundle', 'changes_since', 'export_job')) {
            if (!$listed.Content.Contains($tool)) { throw "Missing tool: $tool" }
        }
    }
    $denied = Invoke-WebRequest -Uri "http://127.0.0.1:$Port/mcp/daemon/status" -SkipHttpErrorCheck
    if ($denied.StatusCode -notin @(401,403)) { throw 'Unauthenticated daemon control was accepted' }
    $null = Invoke-Daemon @('stop')
    if ((Invoke-Daemon @('status')) -notmatch '^STOPPED') { throw 'Daemon did not stop' }
    $null = Invoke-Daemon @('stop')
    $restarted = Invoke-Daemon @('start', '--port', "$Port")
    if ($restarted -notmatch '^READY') { throw 'Restart failed' }
    $third = Get-Content -LiteralPath (Join-Path $smokeRoot 'daemon/state.json') -Raw | ConvertFrom-Json
    if ($third.instance -eq $first.instance) { throw 'Restart did not create a new instance' }
    Write-Output "PASS: duplicate start, two MCP sessions, all four tools, auth rejection, graceful stop and restart. Evidence: $smokeRoot"
} finally {
    try { $null = Invoke-Daemon @('stop') } finally {
        Pop-Location
        [Environment]::SetEnvironmentVariable('TELEGRAM_MCP_DATA_DIR', $null, 'Process')
        foreach ($name in $savedEnvironment.Keys) { [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process') }
    }
}
