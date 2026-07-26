[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, ParameterSetName = 'Jar')]
    [string]$Jar,
    [Parameter(Mandatory = $true, ParameterSetName = 'Executable')]
    [string]$Executable,
    [string[]]$RequiredTool = @(),
    [string[]]$ForbiddenTool = @()
)

$ErrorActionPreference = 'Stop'
$shutdownTimeoutMilliseconds = 10000
$startInfo = [System.Diagnostics.ProcessStartInfo]::new()
$startInfo.FileName = if ($PSCmdlet.ParameterSetName -eq 'Jar') {
    'java'
} else {
    (Resolve-Path -LiteralPath $Executable).Path
}
$startInfo.UseShellExecute = $false
$startInfo.RedirectStandardInput = $true
$startInfo.RedirectStandardOutput = $true
$startInfo.RedirectStandardError = $true
$startInfo.CreateNoWindow = $true
if ($PSCmdlet.ParameterSetName -eq 'Jar') {
    $startInfo.ArgumentList.Add('-jar')
    $startInfo.ArgumentList.Add((Resolve-Path -LiteralPath $Jar).Path)
}
$startInfo.ArgumentList.Add('serve')
$startInfo.ArgumentList.Add('--transport')
$startInfo.ArgumentList.Add('stdio')
@($startInfo.Environment.Keys) |
    Where-Object { $_ -like 'TELEGRAM_ACCOUNTS_*' -or $_ -like 'TDLIB_*' } |
    ForEach-Object { [void]$startInfo.Environment.Remove($_) }
$startInfo.Environment['MCP_READ_ONLY'] = 'true'
$startInfo.Environment['MCP_TOOL_PROFILE'] = 'reader'
$startInfo.Environment['TDLIB_API_ID'] = '0'
$startInfo.Environment['TDLIB_API_HASH'] = ''

$process = [System.Diagnostics.Process]::new()
$process.StartInfo = $startInfo
if (-not $process.Start()) { throw 'Unable to start stdio MCP server' }
$stderrTask = $process.StandardError.ReadToEndAsync()

function Send-JsonLine([hashtable]$Message) {
    $process.StandardInput.WriteLine(($Message | ConvertTo-Json -Depth 10 -Compress))
    $process.StandardInput.Flush()
}

function Read-Response([int]$Id, [int]$TimeoutSeconds = 30) {
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        $readTask = $process.StandardOutput.ReadLineAsync()
        while (-not $readTask.Wait(250)) {
            if ($process.HasExited) {
                throw "STDIO server exited with code $($process.ExitCode): $($stderrTask.Result)"
            }
            if ([DateTimeOffset]::UtcNow -ge $deadline) {
                throw "Timed out waiting for JSON-RPC response id=$Id"
            }
        }
        $line = $readTask.Result
        if ($null -eq $line) { throw "STDIO server closed stdout: $($stderrTask.Result)" }
        try {
            $message = $line | ConvertFrom-Json -Depth 100
        } catch {
            throw "Non-JSON data was written to STDOUT: $line"
        }
        if ($message.id -eq $Id) { return $message }
    }
    throw "Timed out waiting for JSON-RPC response id=$Id"
}

try {
    Send-JsonLine @{
        jsonrpc = '2.0'
        id = 1
        method = 'initialize'
        params = @{
            protocolVersion = '2025-06-18'
            capabilities = @{}
            clientInfo = @{ name = 'telegram-mcp-stdio-smoke'; version = '1.0.0' }
        }
    }
    $initialize = Read-Response 1
    if ($null -ne $initialize.error) { throw "Initialize failed: $($initialize.error | ConvertTo-Json -Compress)" }

    Send-JsonLine @{
        jsonrpc = '2.0'
        method = 'notifications/initialized'
        params = @{}
    }
    Send-JsonLine @{
        jsonrpc = '2.0'
        id = 2
        method = 'tools/list'
        params = @{}
    }
    $toolsResponse = Read-Response 2
    if ($null -ne $toolsResponse.error) { throw "tools/list failed: $($toolsResponse.error | ConvertTo-Json -Compress)" }
    $tools = @($toolsResponse.result.tools)
    $names = @($tools | ForEach-Object { $_.name })

    foreach ($required in $RequiredTool) {
        if ($required -notin $names) { throw "Required stdio tool is missing: $required" }
    }
    foreach ($forbidden in $ForbiddenTool) {
        if ($forbidden -in $names) { throw "Forbidden stdio tool is exposed: $forbidden" }
    }

    $invalid = @($tools | Where-Object {
        $_.inputSchema.type -ne 'object' -or
        $null -eq $_.outputSchema -or
        $null -eq $_.annotations.readOnlyHint
    })
    if ($invalid.Count -gt 0) {
        throw "Invalid stdio tool contracts: $($invalid.name -join ', ')"
    }

    $result = [ordered]@{
        transport = 'stdio'
        protocolVersion = $initialize.result.protocolVersion
        toolCount = $tools.Count
        outputSchemas = $true
        stdoutJsonOnly = $true
    }

    $process.StandardInput.Close()
    if (-not $process.WaitForExit($shutdownTimeoutMilliseconds)) {
        $process.Kill($true)
        if (-not $process.WaitForExit(5000)) {
            throw "STDIO server did not exit within $($shutdownTimeoutMilliseconds)ms after stdin closed and could not be terminated"
        }
        $forcedExitCode = $process.ExitCode
        $stderr = $stderrTask.GetAwaiter().GetResult()
        throw "STDIO server did not exit within $($shutdownTimeoutMilliseconds)ms after stdin closed; " +
            "forced exit code ${forcedExitCode}. STDERR: $stderr"
    }

    $exitCode = $process.ExitCode
    $stderr = $stderrTask.GetAwaiter().GetResult()
    if ($exitCode -ne 0) {
        throw "STDIO server exited with code ${exitCode} after stdin closed. STDERR: $stderr"
    }

    $result['lifecycleExit'] = $true
    $result['exitCode'] = $exitCode
    $result | ConvertTo-Json -Compress
} finally {
    try { $process.StandardInput.Close() } catch {}
    if (-not $process.HasExited) {
        try {
            $process.Kill($true)
            [void]$process.WaitForExit(5000)
        } catch {
            if (-not $process.HasExited) { throw }
        }
    }
    $process.Dispose()
}
