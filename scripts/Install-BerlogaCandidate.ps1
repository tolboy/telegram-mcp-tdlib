[CmdletBinding()]
param(
    [string]$BundleDirectory = $PSScriptRoot,
    [string]$ServersPath = (Join-Path $env:APPDATA 'Berloga/servers.yaml')
)
$ErrorActionPreference = 'Stop'
$manifest = Get-Content -LiteralPath (Join-Path $BundleDirectory 'berloga-artifact.json') -Raw | ConvertFrom-Json
if ($manifest.schemaVersion -ne 1 -or $manifest.image -notmatch '^berloga/telegram-mcp-tdlib:[0-9A-Za-z._-]+$') {
    throw 'Unsupported Berloga candidate manifest'
}
$archive = Join-Path $BundleDirectory 'telegram-mcp-docker.tar'
if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $manifest.archiveSha256) {
    throw 'Docker archive checksum mismatch'
}
$configPath = (Resolve-Path -LiteralPath $ServersPath).Path
$config = [IO.File]::ReadAllText($configPath)
# Match only the Telegram image; never parse or print credentials in the file.
# Refuse unfamiliar layouts instead of rewriting arbitrary YAML.
$pattern = '(?m)^(\s*image:\s*)["'']?(?:ghcr\.io/tolboy|berloga)/telegram-mcp-tdlib(?::[^\s"''#]+|@sha256:[a-f0-9]+)["'']?(\s*(?:#.*)?)$'
$matches = [regex]::Matches($config, $pattern)
if ($matches.Count -ne 1) { throw 'Expected exactly one supported Telegram MCP image in servers.yaml' }
& docker load --input $archive
if ($LASTEXITCODE -ne 0) { throw 'docker load failed; configuration unchanged' }
$imageId = (& docker image inspect --format '{{.Id}}' $manifest.image).Trim()
if ($LASTEXITCODE -ne 0 -or $imageId -ne $manifest.imageConfigId) {
    throw 'Loaded image identity mismatch; configuration unchanged'
}
$replacement = [regex]::Replace($config, $pattern, {
    param($match)
    $match.Groups[1].Value + $manifest.image + $match.Groups[2].Value
})
if ($replacement -ne $config) {
    $backup = $configPath + '.before-mcp-' + [DateTime]::UtcNow.ToString('yyyyMMddHHmmssfff') + '.bak'
    Copy-Item -LiteralPath $configPath -Destination $backup
    [IO.File]::WriteAllText($configPath, $replacement, [Text.UTF8Encoding]::new($false))
    Write-Host "Configuration backed up beside servers.yaml. Keep that backup private."
}
Write-Host "Loaded $($manifest.image). In Berloga, open Connectors and click Apply to reconcile."
Write-Host 'Existing credentials and TDLib volumes are preserved. This is a local release candidate.'
