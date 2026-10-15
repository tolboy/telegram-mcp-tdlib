param([string]$Worker, [string]$Config, [string]$TaskName)
$ErrorActionPreference = 'Stop'
try {
    if ($TaskName -notmatch '^TelegramMcpDaemon-[0-9a-f-]{36}$') { throw 'Invalid daemon task identity' }
    $account = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
    $principal = New-ScheduledTaskPrincipal -UserId $account -LogonType Interactive -RunLevel Limited
    $powershell = Join-Path $PSHOME 'powershell.exe'
    # Execute the trusted bundled worker as command text, without changing the
    # user's script execution policy. Only private paths, not the environment
    # values, enter the task action. Escape PowerShell literals explicitly.
    $workerCode = [IO.File]::ReadAllText($Worker)
    $workerInvocation = "& {`n" + $workerCode + "`n} -Config '" + $Config.Replace("'", "''") + "' -TaskName '" + $TaskName.Replace("'", "''") + "'"
    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($workerInvocation))
    $arguments = '-NoProfile -NonInteractive -WindowStyle Hidden -EncodedCommand ' + $encoded
    $action = New-ScheduledTaskAction -Execute $powershell -Argument $arguments
    $settings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([TimeSpan]::Zero) -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
    # No trigger: this task runs only on demand, with the current interactive
    # user's token. Its worker unregisters it after the daemon exits.
    $null = Register-ScheduledTask -TaskName $TaskName -Action $action -Principal $principal -Settings $settings
    Start-ScheduledTask -TaskName $TaskName
} catch {
    if ($TaskName -match '^TelegramMcpDaemon-[0-9a-f-]{36}$') {
        Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false -ErrorAction SilentlyContinue
    }
    Write-Error 'Could not register/start the on-demand daemon task for the current user.'
    exit 1
}
