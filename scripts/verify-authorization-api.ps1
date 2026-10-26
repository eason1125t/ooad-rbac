# 功能：只读核验本机demo应用授权API；使用显式演示目录登录，不配置委派、不改角色、不输出人员或会话。
param([string]$BaseUrl='http://127.0.0.1:8080')
$ErrorActionPreference='Stop'
$taskRoot=Split-Path $PSScriptRoot -Parent
$BaseUrl=$BaseUrl.TrimEnd('/')
$taskUri=[uri]$BaseUrl
if($taskUri.Host -notin @('127.0.0.1','localhost') -or $taskUri.Scheme -ne 'http'){throw 'Only loopback demo is supported'}
$taskProfile=(Get-Content -LiteralPath (Join-Path $taskRoot '.env')|Where-Object {$_ -match '^SPRING_PROFILES_ACTIVE='}) -replace '^SPRING_PROFILES_ACTIVE=',''
if('demo' -notin (($taskProfile -split ',')|ForEach-Object {$_.Trim()})){throw 'Explicit demo profile required'}
$taskCatalog=Get-Content -LiteralPath (Join-Path $taskRoot 'backend/src/main/resources/demo/accounts.json') -Raw|ConvertFrom-Json
$taskChecks=[Collections.Generic.List[object]]::new()
function Test-TaskResponse([string]$Name,$Response,[int]$Expected){
    $taskChecks.Add(@{name=$Name;expected=$Expected;actual=[int]$Response.StatusCode})
    if($Response.StatusCode -ne $Expected){throw ('Unexpected HTTP status: '+$Name)}
    if($Expected -eq 200 -and $Response.Headers['Cache-Control'] -notmatch 'no-store'){throw 'Expected no-store'}
    if($Response.Content -match '"passwordHash"|"password_hash"|"temporaryPassword"'){throw 'Secret field exposed'}
}
$taskSession=[Microsoft.PowerShell.Commands.WebRequestSession]::new()
$taskCsrf=Invoke-RestMethod "$BaseUrl/api/v1/auth/csrf" -WebSession $taskSession
Test-TaskResponse 'login' (Invoke-WebRequest "$BaseUrl/api/v1/auth/login" -Method Post -WebSession $taskSession -Headers @{'X-CSRF-TOKEN'=$taskCsrf.data.token} -ContentType 'application/x-www-form-urlencoded' -Body @{username='super-admin';password=$taskCatalog.password} -SkipHttpErrorCheck) 200
$taskApps=Invoke-WebRequest "$BaseUrl/api/v1/admin/managed-applications" -WebSession $taskSession -SkipHttpErrorCheck
Test-TaskResponse 'managed applications' $taskApps 200
foreach($taskApp in ($taskApps.Content|ConvertFrom-Json).data.items){
    $taskAppId=$taskApp.id
    foreach($taskPath in @('permissions','roles','people','delegations')){
        Test-TaskResponse ($taskApp.code+' '+$taskPath) (Invoke-WebRequest "$BaseUrl/api/v1/admin/applications/$taskAppId/$taskPath" -WebSession $taskSession -SkipHttpErrorCheck) 200
    }
    Test-TaskResponse ($taskApp.code+' own effective') (Invoke-WebRequest "$BaseUrl/api/v1/auth/effective-permissions?applicationId=$taskAppId" -WebSession $taskSession -SkipHttpErrorCheck) 200
}
$taskCsrf=Invoke-RestMethod "$BaseUrl/api/v1/auth/csrf" -WebSession $taskSession
Test-TaskResponse 'logout' (Invoke-WebRequest "$BaseUrl/api/v1/auth/logout" -Method Post -WebSession $taskSession -Headers @{'X-CSRF-TOKEN'=$taskCsrf.data.token} -SkipHttpErrorCheck) 200
New-Item -ItemType Directory -Path (Join-Path $taskRoot 'reports/environment') -Force|Out-Null
[ordered]@{status='PASS';baseUrl=$BaseUrl;checkedAt=[DateTimeOffset]::Now.ToString('o');checks=$taskChecks}|ConvertTo-Json -Depth 5|Set-Content -LiteralPath (Join-Path $taskRoot 'reports/environment/authorization-api-check.json') -Encoding utf8
Write-Output 'Live authorization API PASS: application/role/people/delegation reads, own effective scopes, no secrets/cache and logout.'
