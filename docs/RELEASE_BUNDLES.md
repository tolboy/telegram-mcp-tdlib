# Release Bundles

Every `vX.Y.Z` tag creates a GitHub release with a checked runtime-inclusive
app image for each supported TDLight target. Each archive includes a native
launcher, the matching Java runtime and the runnable Spring Boot JAR.

| Release archive target | Launcher | Bundled runtime |
|---|---|---|
| Windows x64 | `telegram-mcp.exe` | Java + `windows_amd64` |
| Linux x64 | `telegram-mcp/bin/telegram-mcp` | Java + `linux_amd64_gnu_ssl3` |
| Linux ARM64 | `telegram-mcp/bin/telegram-mcp` | Java + `linux_arm64_gnu_ssl3` |
| macOS Apple silicon | `telegram-mcp.app/Contents/MacOS/telegram-mcp` | Java + `macos_arm64` |

App-image launchers do not require Java, Gradle, Git, Python or Node.js on
`PATH`. The standalone JAR remains available for managed deployments and
requires Java 25 or newer.

Intel macOS is not published as a release archive. The current TDLight native
line used by this project has no `macos_amd64` classifier, so shipping an
archive would be misleading: Java would start but TDLib could not initialize.
When TDLight publishes that classifier, add it to `build.gradle.kts`, extend
`verifyNativeRuntimeCoverage`, and add the matching target to
`scripts/package-app-image.ps1`.

To produce an app image on its matching platform:

```powershell
./scripts/package-app-image.ps1 -Version <version> -Target windows-x64 -OutputDirectory release-assets
```

The release matrix runs `jpackage` natively on every OS/architecture and
requires each resulting launcher to pass `version` plus a real STDIO
`initialize` / `tools/list` handshake. The aggregation job writes one
`release-manifest.json` and direct Homebrew/Scoop manifests.

## Supply-chain verification

Once the repository is public, the release workflow publishes four extra trust
signals alongside the bundles:

- `telegram-mcp-server-<version>.jar` — the exact runnable JAR inside every
  platform archive.
- `telegram-mcp-server-<version>.spdx.json` — an SPDX JSON SBOM for that JAR.
- GitHub artifact attestations — SLSA provenance for all release assets and an
  SBOM attestation binding the SBOM to the runnable JAR.
- Keyless Sigstore signatures and provenance attestations for both the HTTP and
  STDIO GHCR image digests.

Verify a downloaded release asset and its provenance with GitHub CLI (replace
the repository and file names):

```powershell
gh attestation verify .\telegram-mcp-server-<version>-windows-x64.zip --repo <owner>/telegram-mcp-tdlib
```

For a container, first resolve the digest shown by your registry or `docker
buildx imagetools inspect`, then verify the keyless signature:

```sh
cosign verify \
  --certificate-identity-regexp='https://github.com/<owner>/telegram-mcp-tdlib/.github/workflows/docker-build.yml@refs/tags/v.*' \
  --certificate-oidc-issuer='https://token.actions.githubusercontent.com' \
  ghcr.io/<owner>/telegram-mcp-tdlib@sha256:<digest>
```

The SBOM is also a normal release asset, so it remains inspectable even when a
consumer does not use GitHub attestations.

## Platform surface smoke

`Platform MCP Surface Smoke` is a manual GitHub Actions workflow. It builds
the same runnable JAR on Linux x64, Windows x64, and the `macos-14`
Apple-silicon runner; each job starts the server, waits for `/actuator/health`,
and performs both Streamable HTTP and STDIO `initialize` / `tools/list`
handshakes. The
three jobs also assert their selected `inbox`, `reader`, or `research` profile
has the expected visible and hidden tools.

It deliberately does not authenticate a Telegram account or invoke TDLib. Live
account coverage has different failure modes and must stay in a separately
controlled E2E environment.
