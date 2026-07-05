[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9]+\.[0-9]+\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?$')]
    [string]$Version,
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '..\release-assets')
)

$ErrorActionPreference = 'Stop'
$resolvedOutput = (Resolve-Path -LiteralPath $OutputDirectory).Path
$jar = Get-Item -LiteralPath (Join-Path $resolvedOutput "telegram-mcp-server-$Version.jar")
$packages = Get-ChildItem -LiteralPath $resolvedOutput -Filter 'release-package-*.json' |
    Sort-Object Name |
    ForEach-Object { Get-Content -Raw -LiteralPath $_.FullName | ConvertFrom-Json }

$requiredTargets = @('windows-x64','linux-x64','linux-arm64','macos-arm64')
$actualTargets = @($packages.target)
$missing = @($requiredTargets | Where-Object { $_ -notin $actualTargets })
if ($missing.Count -gt 0) { throw "Missing release app-image target(s): $($missing -join ', ')" }

[ordered]@{
    version = $Version
    java = 'bundled in app images; Java 25+ for the standalone JAR'
    application_jar = [ordered]@{
        file = $jar.Name
        sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $jar.FullName).Hash.ToLowerInvariant()
    }
    packages = @($packages)
    unsupported = @('macos-x64 (Intel): TDLight has not published a matching native classifier')
} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $resolvedOutput 'release-manifest.json')

Get-ChildItem -LiteralPath $resolvedOutput -Filter 'release-package-*.json' |
    Remove-Item -Force
