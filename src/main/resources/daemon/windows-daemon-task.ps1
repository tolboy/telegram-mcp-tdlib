param([string]$Config, [string]$TaskName)
$ErrorActionPreference = 'Stop'
$daemonExit = 1
try {
    $settings = Get-Content -LiteralPath $Config -Raw -Encoding UTF8 | ConvertFrom-Json
    # The environment stays in memory once loaded. It is never embedded in the
    # task definition or printed, and the owner-only bootstrap file is consumed.
    Remove-Item -LiteralPath $Config -Force
    $start = New-Object System.Diagnostics.ProcessStartInfo
    $start.FileName = $settings.application
    $start.Arguments = $settings.arguments
    $start.WorkingDirectory = $settings.directory
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.EnvironmentVariables.Clear()
    foreach ($entry in $settings.environment.PSObject.Properties) {
        $start.EnvironmentVariables[$entry.Name] = [string]$entry.Value
    }
    $start.EnvironmentVariables['TELEGRAM_MCP_DAEMON_TASK'] = $TaskName
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $start
    $null = $process.Start()
    $process.WaitForExit()
    $daemonExit = $process.ExitCode
    $process.Dispose()
} catch {
    if ($settings.log) {
        Add-Content -LiteralPath $settings.log -Encoding UTF8 -Value 'Windows daemon task failed; inspect daemon.log and verify the installed runtime paths.'
    }
} finally {
    Remove-Item -LiteralPath $Config -Force -ErrorAction SilentlyContinue
    if ($TaskName -match '^TelegramMcpDaemon-[0-9a-f-]{36}$') {
        Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false -ErrorAction SilentlyContinue
    }
}
exit $daemonExit
