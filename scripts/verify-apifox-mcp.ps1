# 功能：用用户本机令牌只读验证官方Apifox HTTP MCP，并导出指定项目契约供本地比较。
# 配合.codex/config.toml和contracts/openapi.yaml使用；不记录令牌/会话标识，不修改远程项目。
param([int]$ProjectId = 8967444)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
$taskReports = Join-Path $taskRoot 'reports/environment'
New-Item -ItemType Directory -Path $taskReports -Force | Out-Null
$taskToken = [Environment]::GetEnvironmentVariable('APIFOX_ACCESS_TOKEN','Process')
if([string]::IsNullOrWhiteSpace($taskToken)) {
    $taskToken = [Environment]::GetEnvironmentVariable('APIFOX_ACCESS_TOKEN','User')
}
if([string]::IsNullOrWhiteSpace($taskToken)){throw 'Configure your local Apifox token first'}
$taskHeaders = @{Authorization=('Bearer '+$taskToken);'X-Apifox-Api-Version'='2025-09-01';Accept='application/json, text/event-stream'}
$script:taskRpcId = 0

# 初始化协商后的会话头仅保留在进程内；每个请求只输出经过选择的非秘密结果。
function Invoke-TaskMcp([string]$Method, [hashtable]$Parameters) {
    $script:taskRpcId++
    $taskBody = @{jsonrpc='2.0';id=$script:taskRpcId;method=$Method;params=$Parameters} | ConvertTo-Json -Depth 30 -Compress
    $taskResponse = Invoke-WebRequest -Uri 'https://api.apifox.com/mcp' -Method Post -Headers $taskHeaders -ContentType 'application/json' -Body $taskBody -TimeoutSec 30
    # MCP JSON按UTF-8读取原始响应，避免Windows默认解码把中文替换为乱码。
    $taskJson = [Text.UTF8Encoding]::new($false,$true).GetString($taskResponse.RawContentStream.ToArray())
    $taskParsed = $taskJson | ConvertFrom-Json -Depth 100
    if($taskParsed.error){throw "MCP $Method failed with code $($taskParsed.error.code)"}
    if($taskParsed.result.isError){throw "MCP $Method returned a tool error; inspect the project permissions"}
    if($Method -eq 'initialize') {
        $taskHeaders['Mcp-Session-Id'] = $taskResponse.Headers['mcp-session-id'] -join ''
        $taskHeaders['MCP-Protocol-Version'] = $taskParsed.result.protocolVersion
    }
    return $taskParsed.result
}

try {
    $taskInit = Invoke-TaskMcp 'initialize' @{protocolVersion='2024-11-05';capabilities=@{};clientInfo=@{name='ooad-rbac-environment-check';version='0.1.0'}}
    $taskNotification = @{jsonrpc='2.0';method='notifications/initialized'} | ConvertTo-Json -Compress
    Invoke-WebRequest -Uri 'https://api.apifox.com/mcp' -Method Post -Headers $taskHeaders -ContentType 'application/json' -Body $taskNotification -TimeoutSec 30 | Out-Null
    $taskTools = Invoke-TaskMcp 'tools/list' @{}
    foreach($taskRequiredTool in @('getProjectSummary','exportData')) {
        if($taskRequiredTool -notin $taskTools.tools.name){throw "Missing MCP tool: $taskRequiredTool"}
    }
    $taskSummary = Invoke-TaskMcp 'tools/call' @{name='getProjectSummary';arguments=@{projectId=$ProjectId}}
    $taskProject = ($taskSummary.content | Where-Object type -eq 'text' | Select-Object -First 1).text | ConvertFrom-Json -Depth 100
    if($taskProject.data.id -ne $ProjectId){throw 'MCP returned an unexpected project; stop before further reads'}
    $taskSummary | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath (Join-Path $taskReports 'apifox-project-summary.json') -Encoding utf8
    $taskEndpoints = Invoke-TaskMcp 'tools/call' @{name='getStructureInfo';arguments=@{projectId=$ProjectId;entityType='endpoint'}}
    $taskEndpoints | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath (Join-Path $taskReports 'apifox-endpoints.json') -Encoding utf8
    $taskEntityList = ($taskEndpoints.content | Where-Object type -eq 'text' | Select-Object -First 1).text | ConvertFrom-Json -Depth 100
    foreach($taskEntity in $taskEntityList.data.entities) {
        if($taskEntity.path -in @('/api/v1/health','/api/v1/auth/csrf','/api/v1/auth/login')) {
            $taskDetails = Invoke-TaskMcp 'tools/call' @{name='getHttpEndpoint';arguments=@{pathParams=@{projectId=$ProjectId;httpApiId=$taskEntity.entityId}}}
            $taskDetails | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath (Join-Path $taskReports "apifox-endpoint-$($taskEntity.entityId).json") -Encoding utf8
        }
    }
    $taskExport = Invoke-TaskMcp 'tools/call' @{name='exportData';arguments=@{pathParams=@{projectId=[string]$ProjectId};body=@{format='json';type=1;version='openapi30';excludeExtension=$false;shouldExportDatabaseConnection=$false;shouldExportProjectSetting=$false;shouldExportTestCase=$false}}}
    $taskExport | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath (Join-Path $taskReports 'apifox-export-result.json') -Encoding utf8
    [ordered]@{projectId=$ProjectId;server=$taskInit.serverInfo;toolCount=$taskTools.tools.Count;projectRead='PASS';exportRead='PASS';contractComparison='PENDING';checkedAt=[DateTime]::UtcNow.ToString('o')} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $taskReports 'apifox-mcp-check.json') -Encoding utf8
    Write-Output "Apifox MCP authentication, $($taskTools.tools.Count) tools, project $ProjectId read and export PASS. Contract comparison is a separate check."
} finally {
    $taskHeaders.Clear()
    $taskToken = $null
}
