[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Version,
    [string]$Repository = 'tolboy/telegram-mcp-tdlib',
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '..\release-assets')
)

$ErrorActionPreference = 'Stop'
$resolvedOutput = (Resolve-Path -LiteralPath $OutputDirectory).Path
$manifest = Get-Content -Raw -LiteralPath (Join-Path $resolvedOutput 'release-manifest.json') | ConvertFrom-Json
$baseUrl = "https://github.com/$Repository/releases/download/v$Version"

function Package([string]$Target) {
    $package = $manifest.packages | Where-Object { $_.target -eq $Target } | Select-Object -First 1
    if ($null -eq $package) { throw "Release manifest has no $Target package" }
    return $package
}

$mac = Package 'macos-arm64'
$linuxX64 = Package 'linux-x64'
$linuxArm64 = Package 'linux-arm64'
$windows = Package 'windows-x64'

$formula = @"
class TelegramMcp < Formula
  desc "Production-minded TDLib Telegram MCP server"
  homepage "https://github.com/$Repository"
  version "$Version"
  license "Apache-2.0"

  on_macos do
    if Hardware::CPU.arm?
      url "$baseUrl/$($mac.file)"
      sha256 "$($mac.sha256)"
    else
      odie "Intel macOS is unavailable because TDLight does not publish a matching native runtime"
    end
  end

  on_linux do
    if Hardware::CPU.arm?
      url "$baseUrl/$($linuxArm64.file)"
      sha256 "$($linuxArm64.sha256)"
    else
      url "$baseUrl/$($linuxX64.file)"
      sha256 "$($linuxX64.sha256)"
    end
  end

  def install
    libexec.install Dir["*"]
    executable = if OS.mac?
      libexec/"telegram-mcp-server-#{version}-macos-arm64/telegram-mcp.app/Contents/MacOS/telegram-mcp"
    elsif Hardware::CPU.arm?
      libexec/"telegram-mcp-server-#{version}-linux-arm64/telegram-mcp/bin/telegram-mcp"
    else
      libexec/"telegram-mcp-server-#{version}-linux-x64/telegram-mcp/bin/telegram-mcp"
    end
    bin.write_exec_script executable
  end

  test do
    assert_match version.to_s, shell_output("#{bin}/telegram-mcp version")
  end
end
"@
$formula | Set-Content -LiteralPath (Join-Path $resolvedOutput 'telegram-mcp.rb')

$scoop = [ordered]@{
    version = $Version
    description = 'Production-minded TDLib Telegram MCP server'
    homepage = "https://github.com/$Repository"
    license = 'Apache-2.0'
    architecture = [ordered]@{
        '64bit' = [ordered]@{
            url = "$baseUrl/$($windows.file)"
            hash = $windows.sha256
        }
    }
    bin = "telegram-mcp-server-$Version-windows-x64\telegram-mcp\telegram-mcp.exe"
    checkver = [ordered]@{ github = "https://github.com/$Repository" }
    autoupdate = [ordered]@{
        architecture = [ordered]@{
            '64bit' = [ordered]@{
                url = "https://github.com/$Repository/releases/download/v`$version/telegram-mcp-server-`$version-windows-x64.zip"
            }
        }
    }
} | ConvertTo-Json -Depth 8
$scoop | Set-Content -LiteralPath (Join-Path $resolvedOutput 'telegram-mcp.json')
