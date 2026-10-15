[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9]+\.[0-9]+\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?$')]
    [string]$Version,
    [Parameter(Mandatory = $true)]
    [ValidateSet('windows-x64', 'linux-x64', 'linux-arm64', 'macos-arm64')]
    [string]$Target,
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '..\release-assets'),
    [string]$JarPath = ''
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$outputPath = if ([IO.Path]::IsPathRooted($OutputDirectory)) { $OutputDirectory } else { Join-Path $repositoryRoot $OutputDirectory }
$resolvedOutput = [IO.Path]::GetFullPath($outputPath)
$temporaryRoot = Join-Path ([IO.Path]::GetTempPath()) ("telegram-mcp-app-image-" + [guid]::NewGuid().ToString('N'))
$inputDirectory = Join-Path $temporaryRoot 'input'
$imageOutput = Join-Path $temporaryRoot 'image'
$bundleRoot = Join-Path $temporaryRoot "telegram-mcp-server-$Version-$Target"

New-Item -ItemType Directory -Force -Path $resolvedOutput,$inputDirectory,$imageOutput,$bundleRoot | Out-Null

try {
    if ([string]::IsNullOrWhiteSpace($JarPath)) {
        $gradleWrapper = Join-Path $repositoryRoot $(if ($IsWindows) { 'gradlew.bat' } else { 'gradlew' })
        $buildDirectory = Join-Path $temporaryRoot 'build'
        & $gradleWrapper bootJar verifyNativeRuntimeCoverage verifyReleaseMetadata "-PreleaseVersion=$Version" "-Pktm.buildDir=$buildDirectory" '--no-daemon'
        if ($LASTEXITCODE -ne 0) { throw "Gradle release build failed with exit code $LASTEXITCODE" }
        $jar = Get-ChildItem -Path $buildDirectory -Recurse -Filter 'telegram-mcp-server.jar' | Select-Object -First 1
    } else {
        $jar = Get-Item -LiteralPath (Resolve-Path -LiteralPath $JarPath)
    }
    if ($null -eq $jar) { throw 'Runnable Boot JAR was not produced' }

    $packagedJar = Join-Path $inputDirectory 'telegram-mcp-server.jar'
    Copy-Item -LiteralPath $jar.FullName -Destination $packagedJar

    $jpackage = Get-Command jpackage -ErrorAction Stop
    $jpackageArguments = @(
        '--type', 'app-image',
        '--name', 'telegram-mcp',
        '--input', $inputDirectory,
        '--main-jar', 'telegram-mcp-server.jar',
        '--dest', $imageOutput,
        '--app-version', $Version.Split('-')[0],
        '--description', 'Production-minded TDLib Telegram MCP server',
        # jpackage otherwise strips native commands, including java/javaw.
        # The managed daemon launches a child JVM from this bundled runtime.
        '--jlink-options', '--strip-debug --no-man-pages --no-header-files',
        '--java-options', '--enable-preview'
    )
    if ($Target -eq 'windows-x64') {
        $jpackageArguments += '--win-console'
    }
    & $jpackage.Source @jpackageArguments
    if ($LASTEXITCODE -ne 0) { throw "jpackage failed with exit code $LASTEXITCODE" }

    $image = if ($Target -eq 'macos-arm64') {
        Get-Item -LiteralPath (Join-Path $imageOutput 'telegram-mcp.app')
    } else {
        Get-Item -LiteralPath (Join-Path $imageOutput 'telegram-mcp')
    }
    $executable = if ($Target -eq 'windows-x64') {
        Join-Path $image.FullName 'telegram-mcp.exe'
    } elseif ($Target -eq 'macos-arm64') {
        Join-Path $image.FullName 'Contents/MacOS/telegram-mcp'
    } else {
        Join-Path $image.FullName 'bin/telegram-mcp'
    }
    $reportedVersion = (& $executable version).Trim()
    if ($reportedVersion -ne $Version) {
        throw "App image reports version '$reportedVersion', expected '$Version'"
    }

    Copy-Item -LiteralPath $image.FullName -Destination $bundleRoot -Recurse
    $supportDirectory = Join-Path $bundleRoot 'support'
    New-Item -ItemType Directory -Path $supportDirectory | Out-Null
    foreach ($supportFile in @('mcp-http-stdio-bridge.py', 'requirements-mcp-client.txt')) {
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot $supportFile) -Destination $supportDirectory
    }
    Copy-Item -LiteralPath (Join-Path $repositoryRoot 'docs/CLAUDE_DESKTOP_BRIDGE.md') -Destination $supportDirectory
    Copy-Item -LiteralPath (Join-Path $repositoryRoot 'LICENSE') -Destination $supportDirectory
    @"
Telegram MCP Server $Version
Target: $Target

The Java runtime is included. No JDK, Gradle, Git, or source checkout is required.

Commands:
  telegram-mcp version
  telegram-mcp auth --method qr
  telegram-mcp serve --transport stdio
  telegram-mcp serve --transport streamable-http

On macOS the executable is telegram-mcp.app/Contents/MacOS/telegram-mcp.
Optional Claude Desktop shared-daemon tools bridge: see support/CLAUDE_DESKTOP_BRIDGE.md.
The bridge additionally requires Python 3.11+ and its pinned SDK dependency.
"@ | Set-Content -LiteralPath (Join-Path $bundleRoot 'README.txt')

    if ($Target -eq 'windows-x64') {
        $archive = Join-Path $resolvedOutput "telegram-mcp-server-$Version-$Target.zip"
        Compress-Archive -Path $bundleRoot -DestinationPath $archive -CompressionLevel Optimal -Force
        $launcher = 'telegram-mcp/telegram-mcp.exe'
    } else {
        $archive = Join-Path $resolvedOutput "telegram-mcp-server-$Version-$Target.tar.gz"
        & tar -C $temporaryRoot -czf $archive ([IO.Path]::GetFileName($bundleRoot))
        if ($LASTEXITCODE -ne 0) { throw "tar failed with exit code $LASTEXITCODE" }
        $launcher = if ($Target -eq 'macos-arm64') {
            'telegram-mcp.app/Contents/MacOS/telegram-mcp'
        } else {
            'telegram-mcp/bin/telegram-mcp'
        }
    }

    $smokeExtractRoot = Join-Path $temporaryRoot 'smoke-extract'
    New-Item -ItemType Directory -Force -Path $smokeExtractRoot | Out-Null
    if ($Target -eq 'windows-x64') {
        Expand-Archive -LiteralPath $archive -DestinationPath $smokeExtractRoot
    } else {
        & tar -C $smokeExtractRoot -xzf $archive
        if ($LASTEXITCODE -ne 0) { throw "Unable to extract release archive for smoke test" }
    }

    $bundleName = [IO.Path]::GetFileName($bundleRoot)
    $archivedExecutable = Join-Path $smokeExtractRoot (Join-Path $bundleName $launcher)
    if (-not (Test-Path -LiteralPath $archivedExecutable -PathType Leaf)) {
        throw "Release archive launcher is missing: $archivedExecutable"
    }

    $previousTdlibDataDir = $env:TDLIB_DATA_DIR
    $env:TDLIB_DATA_DIR = Join-Path $temporaryRoot 'smoke-session'
    try {
        $archiveVersion = (& $archivedExecutable version).Trim()
        if ($archiveVersion -ne $Version) {
            throw "Archived app image reports version '$archiveVersion', expected '$Version'"
        }
        $doctor = (& $archivedExecutable session doctor | Out-String)
        if ($LASTEXITCODE -ne 0 -or $doctor -notmatch 'Telegram MCP session doctor') {
            throw "Archived app-image session doctor failed"
        }
        & (Join-Path $repositoryRoot 'scripts/mcp-stdio-smoke.ps1') `
            -Executable $archivedExecutable `
            -RequiredTool get_history `
            -ForbiddenTool send_message
        if ($LASTEXITCODE -ne 0) { throw "Archived app-image stdio smoke failed with exit code $LASTEXITCODE" }
        $portProbe = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
        $portProbe.Start()
        $daemonSmokePort = $portProbe.LocalEndpoint.Port
        $portProbe.Stop()
        & (Join-Path $repositoryRoot 'scripts/Test-ManagedDaemon.ps1') -Executable $archivedExecutable -Port $daemonSmokePort
    } finally {
        if ($null -eq $previousTdlibDataDir) {
            Remove-Item Env:TDLIB_DATA_DIR -ErrorAction SilentlyContinue
        } else {
            $env:TDLIB_DATA_DIR = $previousTdlibDataDir
        }
    }

    [ordered]@{
        target = $Target
        file = [IO.Path]::GetFileName($archive)
        sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $archive).Hash.ToLowerInvariant()
        launcher = $launcher
        java_runtime = 'bundled'
    } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $resolvedOutput "release-package-$Target.json")

    Write-Output "Created runtime-inclusive $Target app image: $archive"
} finally {
    if (Test-Path -LiteralPath $temporaryRoot) {
        $checkedTemporaryRoot = [IO.Path]::GetFullPath($temporaryRoot)
        $checkedTempParent = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
        if (-not $checkedTemporaryRoot.StartsWith($checkedTempParent, [StringComparison]::OrdinalIgnoreCase) -or
            [IO.Path]::GetFileName($checkedTemporaryRoot) -notlike 'telegram-mcp-app-image-*') {
            throw 'Refusing to clean an unexpected app-image temporary directory'
        }
        Remove-Item -LiteralPath $temporaryRoot -Recurse -Force
    }
}
