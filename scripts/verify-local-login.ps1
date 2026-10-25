# 功能：用私有.env中的开发管理员验证已运行后端的真实Cookie/CSRF/登录/授权/退出链路。
# 与run-backend.ps1共用凭据；仅记录断言和状态码，不输出密码、Cookie或CSRF令牌。
param([string]$BaseUrl = 'http://127.0.0.1:8080')
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
$taskReports = Join-Path $taskRoot 'reports/environment'
New-Item -ItemType Directory -Path $taskReports -Force | Out-Null
$taskCredentials = @{}
foreach($taskLine in Get-Content -LiteralPath (Join-Path $taskRoot '.env')) {
    if($taskLine -match '^(BOOTSTRAP_ADMIN_USERNAME|BOOTSTRAP_ADMIN_PASSWORD|SPRING_PROFILES_ACTIVE)=(.*)$') {
        $taskCredentials[$Matches[1]] = $Matches[2]
    }
}
if('demo' -in ($taskCredentials.SPRING_PROFILES_ACTIVE -split ',')) {
    $taskCatalog=Get-Content -Raw -LiteralPath (Join-Path $taskRoot 'backend/src/main/resources/demo/accounts.json')|ConvertFrom-Json
    $taskCredentials.BOOTSTRAP_ADMIN_USERNAME='super-admin'
    $taskCredentials.BOOTSTRAP_ADMIN_PASSWORD=$taskCatalog.password
}
if(-not $taskCredentials.BOOTSTRAP_ADMIN_USERNAME -or -not $taskCredentials.BOOTSTRAP_ADMIN_PASSWORD){throw 'Local verification credentials are required in the ignored .env'}
$taskSession = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
$taskSteps = [Collections.Generic.List[object]]::new()
$taskReport = [ordered]@{status='RUNNING';baseUrl=$BaseUrl;checks=$taskSteps;errorStage=$null;checkedAt=[DateTime]::UtcNow.ToString('o')}
$taskStage = 'startup'

function Invoke-TaskRequest([string]$Method,[string]$Path,[hashtable]$Headers=@{},$Body=$null) {
    $taskArguments = @{Uri=($BaseUrl+$Path);Method=$Method;WebSession=$taskSession;Headers=$Headers;SkipHttpErrorCheck=$true;TimeoutSec=10}
    if($null -ne $Body){$taskArguments.Body=$Body;$taskArguments.ContentType='application/x-www-form-urlencoded'}
    return Invoke-WebRequest @taskArguments
}

function Assert-TaskStatus([string]$Name,$Response,[int]$Expected) {
    $taskSteps.Add(@{name=$Name;expected=$Expected;actual=[int]$Response.StatusCode})
    if($Response.StatusCode -ne $Expected){throw "Unexpected HTTP status at $Name"}
}

try {
    $taskStage='health-and-anonymous'
    Assert-TaskStatus 'health' (Invoke-TaskRequest 'GET' '/api/v1/health') 200
    Assert-TaskStatus 'anonymous-denied' (Invoke-TaskRequest 'GET' '/api/v1/oa/demo') 401
    $taskMissingCsrf = Invoke-TaskRequest 'POST' '/api/v1/auth/login' @{} @{username='employee-1';password='1234'}
    Assert-TaskStatus 'missing-csrf-denied' $taskMissingCsrf 403
    $taskStage='csrf-and-login'
    $taskCsrf = Invoke-TaskRequest 'GET' '/api/v1/auth/csrf'
    Assert-TaskStatus 'csrf' $taskCsrf 200
    # 403响应可能已创建CSRF会话，后续GET可复用Cookie而不重复Set-Cookie；检查首次签发属性。
    $taskStage='cookie-attributes'
    $taskCookieHeaders = (@($taskMissingCsrf.Headers['Set-Cookie']) + @($taskCsrf.Headers['Set-Cookie'])) -join ';'
    if($taskCookieHeaders -notmatch 'HttpOnly' -or $taskCookieHeaders -notmatch 'SameSite=Lax'){throw 'Expected development session cookie attributes'}
    $taskToken = ($taskCsrf.Content|ConvertFrom-Json).data
    $taskOriginalCookie = $taskSession.Cookies.GetCookies([Uri]$BaseUrl)['SESSION'].Value
    $taskStage='login'
    $taskLogin = Invoke-TaskRequest 'POST' '/api/v1/auth/login' @{$taskToken.headerName=$taskToken.token} @{username=$taskCredentials.BOOTSTRAP_ADMIN_USERNAME;password=$taskCredentials.BOOTSTRAP_ADMIN_PASSWORD}
    Assert-TaskStatus 'login' $taskLogin 200
    $taskCurrentCookie = $taskSession.Cookies.GetCookies([Uri]$BaseUrl)['SESSION'].Value
    if(-not $taskCurrentCookie -or $taskCurrentCookie -eq $taskOriginalCookie){throw 'Expected session identifier rotation after login'}
    $taskStage='identity-and-permissions'
    $taskMe = Invoke-TaskRequest 'GET' '/api/v1/auth/me'
    Assert-TaskStatus 'me' $taskMe 200
    $taskIdentity = ($taskMe.Content|ConvertFrom-Json).data
    if($taskIdentity.id -isnot [string] -or $taskIdentity.id -notmatch '^\d+$'){throw 'Expected decimal string identity'}
    foreach($taskApplication in @('admin','oa','pmis')) {
        $taskDemo = Invoke-TaskRequest 'GET' "/api/v1/$taskApplication/demo"
        Assert-TaskStatus "$taskApplication-permission" $taskDemo 200
        $taskPayload = ($taskDemo.Content|ConvertFrom-Json).data
        if($taskPayload.application -ne $taskApplication -or $taskPayload.status -ne 'SKELETON'){throw 'Expected honest application skeleton response'}
    }
    $taskStage='logout'
    $taskFreshCsrf = Invoke-TaskRequest 'GET' '/api/v1/auth/csrf'
    Assert-TaskStatus 'fresh-csrf' $taskFreshCsrf 200
    $taskToken = ($taskFreshCsrf.Content|ConvertFrom-Json).data
    Assert-TaskStatus 'logout' (Invoke-TaskRequest 'POST' '/api/v1/auth/logout' @{$taskToken.headerName=$taskToken.token} '') 200
    Assert-TaskStatus 'revoked-session-denied' (Invoke-TaskRequest 'GET' '/api/v1/auth/me') 401
    $taskReport.status='PASS'
    Write-Output 'Real local login PASS: health, anonymous401, CSRF403/200, login, cookie flags/rotation, me, three permission demos, logout and revoked-session401.'
} catch {
    $taskReport.status='FAIL'
    $taskReport.errorStage=$taskStage
    throw "Local login verification failed at $taskStage; inspect backend logs without publishing credentials"
} finally {
    $taskReport|ConvertTo-Json -Depth 8|Set-Content -LiteralPath (Join-Path $taskReports 'local-login-check.json') -Encoding utf8
    $taskCredentials.Clear()
    $taskToken=$null
    $taskSession=$null
}
