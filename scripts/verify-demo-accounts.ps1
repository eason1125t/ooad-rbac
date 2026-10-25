# 功能：根据唯一demo账号目录验证全部账号的真实登录与三应用允许/拒绝矩阵；不输出Cookie、令牌或口令。
# 需要已启动的demo后端和MySQL/Redis，与DemoAccountsInitializer共用JSON定义。
param([string]$BaseUrl='http://127.0.0.1:8080')
$ErrorActionPreference='Stop'
$taskRoot=Split-Path $PSScriptRoot -Parent
$taskCatalog=Get-Content -Raw -LiteralPath (Join-Path $taskRoot 'backend/src/main/resources/demo/accounts.json')|ConvertFrom-Json
$taskRows=[Collections.Generic.List[object]]::new()
foreach($taskAccount in $taskCatalog.accounts){
    $taskSession=[Microsoft.PowerShell.Commands.WebRequestSession]::new()
    $taskCsrf=Invoke-RestMethod "$BaseUrl/api/v1/auth/csrf" -WebSession $taskSession -TimeoutSec 10
    $taskLogin=Invoke-WebRequest "$BaseUrl/api/v1/auth/login" -Method Post -WebSession $taskSession -Headers @{$taskCsrf.data.headerName=$taskCsrf.data.token} -ContentType 'application/x-www-form-urlencoded' -Body @{username=$taskAccount.username;password=$taskCatalog.password} -SkipHttpErrorCheck -TimeoutSec 10
    if($taskLogin.StatusCode-ne200){throw "Demo login failed: $($taskAccount.username)"}
    $taskPermissions=@()
    if('bootstrap_admin'-in$taskAccount.roles){$taskPermissions=@('admin:console:view','oa:document:view','pmis:project:view')}
    foreach($taskRole in $taskCatalog.roles){if($taskRole.code-in$taskAccount.roles){$taskPermissions+=@($taskRole.permissions)}}
    foreach($taskApp in @('admin','oa','pmis')){
        $taskPermission=@{admin='admin:console:view';oa='oa:document:view';pmis='pmis:project:view'}[$taskApp]
        $taskExpected=if($taskPermission-in$taskPermissions){200}else{403}
        $taskResponse=Invoke-WebRequest "$BaseUrl/api/v1/$taskApp/demo" -WebSession $taskSession -SkipHttpErrorCheck -TimeoutSec 10
        $taskRows.Add(@{username=$taskAccount.username;application=$taskApp;expected=$taskExpected;actual=[int]$taskResponse.StatusCode})
        if($taskResponse.StatusCode-ne$taskExpected){throw "Demo access matrix mismatch: $($taskAccount.username)/$taskApp"}
    }
    $taskCsrf=Invoke-RestMethod "$BaseUrl/api/v1/auth/csrf" -WebSession $taskSession -TimeoutSec 10
    Invoke-WebRequest "$BaseUrl/api/v1/auth/logout" -Method Post -WebSession $taskSession -Headers @{$taskCsrf.data.headerName=$taskCsrf.data.token} -TimeoutSec 10|Out-Null
}
[ordered]@{status='PASS';accountCount=$taskCatalog.accounts.Count;checks=$taskRows;checkedAt=[DateTime]::UtcNow.ToString('o')}|ConvertTo-Json -Depth 7|Set-Content -LiteralPath (Join-Path $taskRoot 'reports/environment/demo-accounts-check.json') -Encoding utf8
Write-Output "Demo login/access matrix PASS: $($taskCatalog.accounts.Count) accounts, $($taskRows.Count) application checks."
