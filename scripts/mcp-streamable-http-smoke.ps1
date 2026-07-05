[CmdletBinding()]
param(
    [Parameter()]
    [string]$Endpoint = "http://127.0.0.1:8080/mcp",

    [Parameter()]
    [string]$ApiKey = "",

    [Parameter()]
    [string[]]$RequiredTool = @(),

    [Parameter()]
    [string[]]$ForbiddenTool = @()
)

$ErrorActionPreference = "Stop"
$protocolVersion = "2025-06-18"

function Invoke-McpRequest {
    param(
        [Parameter(Mandatory)]
        [hashtable]$Body,
        [Parameter()]
        [string]$SessionId = ""
    )

    $headers = @{
        "Accept" = "application/json, text/event-stream"
        "MCP-Protocol-Version" = $protocolVersion
    }
    if ($ApiKey) {
        $headers["Authorization"] = "Bearer $ApiKey"
    }
    if ($SessionId) {
        $headers["Mcp-Session-Id"] = $SessionId
    }

    Invoke-WebRequest `
        -Uri $Endpoint `
        -Method POST `
        -ContentType "application/json" `
        -Headers $headers `
        -Body ($Body | ConvertTo-Json -Compress -Depth 10) `
        -TimeoutSec 15
}

function ConvertFrom-McpPayload {
    param([Parameter(Mandatory)][string]$Content)

    # Streamable HTTP may return either application/json or a one-message SSE
    # envelope. Join all data lines to support JSON payloads split by a proxy.
    $json = $Content.Trim()
    if ($json -match "(?m)^data:") {
        $json = (($json -split "`r?`n" | Where-Object { $_ -like "data:*" } |
            ForEach-Object { $_.Substring(5).TrimStart() }) -join "`n")
    }
    $json | ConvertFrom-Json
}

$initialize = Invoke-McpRequest -Body @{
    jsonrpc = "2.0"
    id = 1
    method = "initialize"
    params = @{
        protocolVersion = $protocolVersion
        capabilities = @{}
        clientInfo = @{ name = "telegram-mcp-streamable-smoke"; version = "1.0.0" }
    }
}
$sessionId = @($initialize.Headers["Mcp-Session-Id"])[0]
if ([string]::IsNullOrWhiteSpace($sessionId)) {
    throw "Server did not return Mcp-Session-Id during initialize"
}
$initializeResult = ConvertFrom-McpPayload $initialize.Content
if ($initializeResult.result.protocolVersion -ne $protocolVersion) {
    throw "Server negotiated '$($initializeResult.result.protocolVersion)' instead of '$protocolVersion'"
}

# Required MCP lifecycle notification. Servers normally return HTTP 202/204.
try {
    [void](Invoke-McpRequest -SessionId $sessionId -Body @{
        jsonrpc = "2.0"
        method = "notifications/initialized"
        params = @{}
    })
} catch {
    # Spring's Streamable transport closes the connection after a successful
    # no-response notification. The following session-bound tools/list call is
    # the authoritative confirmation that the notification/session succeeded.
    if ($_.Exception.Message -notmatch "response ended prematurely") { throw }
}

$toolList = Invoke-McpRequest -SessionId $sessionId -Body @{
    jsonrpc = "2.0"
    id = 2
    method = "tools/list"
    params = @{}
}
$tools = @((ConvertFrom-McpPayload $toolList.Content).result.tools)
if ($tools.Count -eq 0) {
    throw "Server returned no MCP tools"
}
$invalidTools = @($tools | Where-Object {
    $_.inputSchema.type -ne "object" -or
    $null -eq $_.inputSchema.properties -or
    $_.outputSchema.type -ne "object" -or
    $null -eq $_.outputSchema.properties.data -or
    $null -eq $_.outputSchema.properties.meta
})
if ($invalidTools.Count -gt 0) {
    throw "Tools without portable input/output schemas: $($invalidTools.name -join ', ')"
}
$unannotatedTools = @($tools | Where-Object {
    $null -eq $_.annotations -or
    $null -eq $_.annotations.readOnlyHint -or
    $null -eq $_.annotations.destructiveHint -or
    $null -eq $_.annotations.idempotentHint -or
    $null -eq $_.annotations.openWorldHint
})
if ($unannotatedTools.Count -gt 0) {
    throw "Tools without complete MCP behavior annotations: $($unannotatedTools.name -join ', ')"
}
$toolNames = @($tools.name)
$missingTools = @($RequiredTool | Where-Object { $_ -notin $toolNames })
if ($missingTools.Count -gt 0) {
    throw "Expected tool(s) missing from MCP surface: $($missingTools -join ', ')"
}
$exposedForbiddenTools = @($ForbiddenTool | Where-Object { $_ -in $toolNames })
if ($exposedForbiddenTools.Count -gt 0) {
    throw "Unexpected tool(s) exposed by MCP surface: $($exposedForbiddenTools -join ', ')"
}

[pscustomobject]@{
    endpoint = $Endpoint
    protocolVersion = $initializeResult.result.protocolVersion
    server = $initializeResult.result.serverInfo.name
    serverVersion = $initializeResult.result.serverInfo.version
    toolCount = $tools.Count
    annotatedToolCount = $tools.Count - $unannotatedTools.Count
    readOnlySurface = -not [bool]($tools.name -contains "send_message")
    requiredTools = $RequiredTool
    forbiddenTools = $ForbiddenTool
    status = "PASS"
} | ConvertTo-Json
