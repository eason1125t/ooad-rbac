# 功能：只读验证已运行的本机demo身份管理API；与demo账号目录、契约配合，不输出凭据或人员明细。
param([string]$BaseUrl='http://127.0.0.1:8080')
$ErrorActionPreference='Stop'
$taskRoot=Split-Path $PSScriptRoot -Parent
$BaseUrl=$BaseUrl.TrimEnd('/')
New-Item -ItemType Directory -Path (Join-Path $taskRoot 'reports/environment') -Force|Out-Null
$taskUri=[uri]$BaseUrl
if($taskUri.Host -notin @('127.0.0.1','localhost') -or $taskUri.Scheme -ne 'http'){throw 'Only the loopback demo environment is supported'}
$taskProfile=(Get-Content -LiteralPath (Join-Path $taskRoot '.env')|Where-Object {$_ -match '^SPRING_PROFILES_ACTIVE='}) -replace '^SPRING_PROFILES_ACTIVE=',''
if('demo' -notin (($taskProfile -split ',')|ForEach-Object {$_.Trim()})){throw 'Explicit local demo profile required'}
$taskCatalog=Get-Content -LiteralPath (Join-Path $taskRoot 'backend/src/main/resources/demo/accounts.json') -Raw|ConvertFrom-Json
$taskChecks=[Collections.Generic.List[object]]::new()
function Assert-TaskResponse([string]$Name,$Response,[int]$Expected){
    $taskChecks.Add(@{name=$Name;expected=$Expected;actual=[int]$Response.StatusCode})
    if($Response.StatusCode -ne $Expected){throw ('Unexpected HTTP status: '+$Name)}
}
function New-TaskLogin([string]$Username){
    $taskSession=[Microsoft.PowerShell.Commands.WebRequestSession]::new()
    $taskCsrf=Invoke-RestMethod "$BaseUrl/api/v1/auth/csrf" -WebSession $taskSession
    $taskLogin=Invoke-WebRequest "$BaseUrl/api/v1/auth/login" -Method Post -WebSession $taskSession -Headers @{'X-CSRF-TOKEN'=$taskCsrf.data.token} -ContentType 'application/x-www-form-urlencoded' -Body @{username=$Username;password=$taskCatalog.password} -SkipHttpErrorCheck
    Assert-TaskResponse ($Username+' login') $taskLogin 200
    return $taskSession
}
$taskAnonymous=Invoke-WebRequest "$BaseUrl/api/v1/admin/employees" -SkipHttpErrorCheck
Assert-TaskResponse 'anonymous identity denied' $taskAnonymous 401
$taskAdmin=New-TaskLogin 'super-admin'
foreach($taskPath in @('organizations','employees','accounts?enabled=true','audit')){
    $taskResponse=Invoke-WebRequest "$BaseUrl/api/v1/admin/$taskPath" -WebSession $taskAdmin -SkipHttpErrorCheck
    Assert-TaskResponse ('platform '+$taskPath) $taskResponse 200
    if($taskResponse.Headers['Cache-Control'] -notmatch 'no-store'){throw 'Identity response must not be cached'}
    if($taskResponse.Content -match '"passwordHash"|"password_hash"|"temporaryPassword"'){throw 'Secret field exposed in a read API'}
}
$taskBusiness=New-TaskLogin 'oa-admin'
Assert-TaskResponse 'business identity management denied' (Invoke-WebRequest "$BaseUrl/api/v1/admin/employees" -WebSession $taskBusiness -SkipHttpErrorCheck) 403
Assert-TaskResponse 'business own application preserved' (Invoke-WebRequest "$BaseUrl/api/v1/oa/demo" -WebSession $taskBusiness -SkipHttpErrorCheck) 200
foreach($taskSession in @($taskAdmin,$taskBusiness)){
    $taskCsrf=Invoke-RestMethod "$BaseUrl/api/v1/auth/csrf" -WebSession $taskSession
    Assert-TaskResponse 'logout' (Invoke-WebRequest "$BaseUrl/api/v1/auth/logout" -Method Post -WebSession $taskSession -Headers @{'X-CSRF-TOKEN'=$taskCsrf.data.token} -SkipHttpErrorCheck) 200
}
[ordered]@{status='PASS';baseUrl=$BaseUrl;checkedAt=[DateTimeOffset]::Now.ToString('o');checks=$taskChecks}|ConvertTo-Json -Depth 5|Set-Content -LiteralPath (Join-Path $taskRoot 'reports/environment/identity-api-check.json') -Encoding utf8
Write-Output 'Live identity API PASS: platform reads, business denial, no-secret/no-cache responses, preserved business access and logout.'
