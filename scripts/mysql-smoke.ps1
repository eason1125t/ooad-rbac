# 功能：用指定已安装的MySQL二进制启动项目隔离实例，执行真实迁移测试；不接触用户服务/数据库，不删除文件。
param([Parameter(Mandatory=$true)][string]$MySqlBin, [switch]$CheckApplication)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
$taskStamp = [DateTime]::UtcNow.ToString('yyyyMMddHHmmssfff')
$taskDir = Join-Path $taskRoot ".tools/mysql-smoke-$taskStamp"
$taskData = Join-Path $taskDir 'data'
$taskPort = 13308
$server = Join-Path $MySqlBin 'mysqld.exe'
$client = Join-Path $MySqlBin 'mysql.exe'
$admin = Join-Path $MySqlBin 'mysqladmin.exe'
$taskOriginalEnv = @{}
foreach($taskVariable in @('MYSQL_TEST_URL','DB_URL','DB_USERNAME','DB_PASSWORD','SERVER_PORT','REDIS_PORT','BOOTSTRAP_ADMIN_USERNAME','BOOTSTRAP_ADMIN_PASSWORD','SPRING_PROFILES_ACTIVE')) { $taskOriginalEnv[$taskVariable] = [Environment]::GetEnvironmentVariable($taskVariable,'Process') }
$taskBase = Split-Path $MySqlBin -Parent
New-Item -ItemType Directory -Path $taskData -Force | Out-Null
if (Get-NetTCPConnection -LocalPort $taskPort -State Listen -ErrorAction SilentlyContinue) { throw "Port $taskPort occupied; do not interfere with existing server" }
# --no-defaults确保不读取用户的my.ini；数据目录为本次专用新路径，临时无口令实例仅绑定回环。
& $server --no-defaults --initialize-insecure "--basedir=$taskBase" "--datadir=$taskData" --console *> (Join-Path $taskDir 'initialize.log')
if ($LASTEXITCODE -ne 0) { throw "MySQL initialization failed; see $taskDir" }
$taskArgs = @('--no-defaults', "--basedir=`"$taskBase`"", "--datadir=`"$taskData`"", "--port=$taskPort", '--bind-address=127.0.0.1', '--mysqlx=OFF', '--default-time-zone=+00:00', '--console')
$taskProcess = Start-Process -FilePath $server -ArgumentList $taskArgs -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $taskDir 'server-out.log') -RedirectStandardError (Join-Path $taskDir 'server-error.log')
$taskApplication = $null
try {
    $taskReady = $false
    for($attempt=0; $attempt -lt 30; $attempt++) {
        if($taskProcess.HasExited) { throw "Isolated MySQL exited; see $taskDir" }
        & $client --no-defaults -h 127.0.0.1 "-P$taskPort" -u root -e 'SELECT 1' *> $null
        if($LASTEXITCODE -eq 0) { $taskReady = $true; break }
        Start-Sleep -Seconds 1
    }
    if(-not $taskReady) { throw 'Isolated MySQL did not become ready' }
    & $client --no-defaults -h 127.0.0.1 "-P$taskPort" -u root -e 'CREATE DATABASE rbac_smoke CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs'
    if($LASTEXITCODE -ne 0) { throw 'Create isolated database failed' }
    $env:MYSQL_TEST_URL = "jdbc:mysql://127.0.0.1:$taskPort/rbac_smoke?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"
    & (Join-Path $taskRoot 'backend/mvnw.cmd') -f (Join-Path $taskRoot 'backend/pom.xml') -B '-Dtest=MySqlMigrationTest' test
    if($LASTEXITCODE -ne 0) { throw 'Real MySQL migration test failed' }
    if($CheckApplication) {
        if(Get-NetTCPConnection -LocalPort 18080 -State Listen -ErrorAction SilentlyContinue) { throw '18080 is occupied' }
        & $client --no-defaults -h 127.0.0.1 "-P$taskPort" -u root -e 'CREATE DATABASE rbac_http CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs'
        $env:DB_URL = "jdbc:mysql://127.0.0.1:$taskPort/rbac_http?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"
        $env:DB_USERNAME = 'root'
        $env:DB_PASSWORD = ''
        $env:SERVER_PORT = '18080'
        $env:REDIS_PORT = '16380'
        $env:SPRING_PROFILES_ACTIVE = 'demo'
        $env:BOOTSTRAP_ADMIN_USERNAME = ''
        $env:BOOTSTRAP_ADMIN_PASSWORD = ''
        $taskJar = Join-Path $taskRoot 'backend/target/rbac-workbench-0.1.0-SNAPSHOT.jar'
        $taskJava = (Get-Command java).Source
        $taskApplication = Start-Process -FilePath $taskJava -ArgumentList @('-jar',"`"$taskJar`"") -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $taskDir 'application-out.log') -RedirectStandardError (Join-Path $taskDir 'application-error.log')
        $taskUp = $false
        for($attempt=0; $attempt -lt 40; $attempt++) {
            if($taskApplication.HasExited) { throw "Application exited; see $taskDir" }
            try { $health = Invoke-WebRequest 'http://127.0.0.1:18080/api/v1/health'; if($health.StatusCode -eq 200){$taskUp=$true;break} } catch {}
            Start-Sleep -Seconds 1
        }
        if(-not $taskUp) { throw 'Application did not start' }
        $taskCsrf = Invoke-WebRequest 'http://127.0.0.1:18080/api/v1/auth/csrf' -SkipHttpErrorCheck
        if($taskCsrf.StatusCode -ne 503) { throw "Expected Redis-down CSRF 503, got $($taskCsrf.StatusCode)" }
        $taskError = $taskCsrf.Content | ConvertFrom-Json
        if($taskError.code -ne 'DEPENDENCY_UNAVAILABLE' -or [string]::IsNullOrWhiteSpace($taskError.traceId)) { throw 'Expected safe JSON infrastructure error with traceId' }
        # health只代表HTTP存活；ApplicationRunner演示事务可能仍在创建账号，需等待提交后再验收。
        $taskAdminCount='0'
        for($taskSeedAttempt=0;$taskSeedAttempt-lt20;$taskSeedAttempt++) {
            if($taskApplication.HasExited){throw 'Application exited during demo initialization'}
            $taskAdminCount = & $client --no-defaults -h 127.0.0.1 "-P$taskPort" -u root --skip-column-names rbac_http -e "SELECT COUNT(*) FROM id_user u JOIN auth_user_role ur ON ur.user_id=u.id WHERE u.username='super-admin' AND ur.role_id=1"
            if($LASTEXITCODE-eq0 -and $taskAdminCount-eq'1'){break}
            Start-Sleep -Milliseconds 500
        }
        if($taskAdminCount -ne '1') { throw 'Environment administrator was not initialized' }
        $taskDenied = Invoke-WebRequest 'http://127.0.0.1:18080/api/v1/oa/demo' -SkipHttpErrorCheck
        if($taskDenied.StatusCode -ne 401) { throw "Expected unauthenticated 401, got $($taskDenied.StatusCode)" }
        Write-Output 'Packaged application PASS: startup + real bootstrap, health200, Redis-down503, anonymous401 (not a Redis login E2E)'
    }
} finally {
    # Windows mysqld可能启动子进程，先核对监听者的数据目录再用mysqladmin关闭整个隔离实例。
    $taskListener = Get-NetTCPConnection -LocalPort $taskPort -State Listen -ErrorAction SilentlyContinue
    if($taskListener) {
        $taskOwner = Get-CimInstance Win32_Process -Filter "ProcessId=$($taskListener.OwningProcess)"
        if($taskOwner.CommandLine.Contains($taskData)) { & $admin --no-defaults -h 127.0.0.1 "-P$taskPort" -u root shutdown }
    }
    # 保留隔离数据供人工检查，禁止批量清理。
    if(-not $taskProcess.HasExited) { Stop-Process -Id $taskProcess.Id }
    if($taskApplication -and -not $taskApplication.HasExited) { Stop-Process -Id $taskApplication.Id }
    foreach($taskVariable in $taskOriginalEnv.Keys) { [Environment]::SetEnvironmentVariable($taskVariable,$taskOriginalEnv[$taskVariable],'Process') }
}
Write-Output "Real MySQL smoke PASS; isolated files retained at $taskDir"
