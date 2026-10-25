# 功能：读取项目私有.env后启动唯一后端；只解析KEY=VALUE而不执行文本，与Compose共用开发凭据。
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
$taskEnvFile = Join-Path $taskRoot '.env'
if(-not (Test-Path -LiteralPath $taskEnvFile)) { throw 'Copy .env.example to .env and fill development credentials first' }
$taskAllowed = @('DB_URL','DB_USERNAME','DB_PASSWORD','REDIS_HOST','REDIS_PORT','REDIS_PASSWORD','BOOTSTRAP_ADMIN_USERNAME','BOOTSTRAP_ADMIN_PASSWORD','SERVER_PORT','SESSION_IDLE_TIMEOUT','SESSION_ABSOLUTE_TIMEOUT','SPRING_PROFILES_ACTIVE','DEMO_RETIRED_USERNAME')
foreach($taskLine in Get-Content -LiteralPath $taskEnvFile) {
    if($taskLine -match '^\s*([A-Z_]+)=(.*)$' -and $Matches[1] -in $taskAllowed) {
        [Environment]::SetEnvironmentVariable($Matches[1],$Matches[2],'Process')
    }
}
& (Join-Path $taskRoot 'backend/mvnw.cmd') -f (Join-Path $taskRoot 'backend/pom.xml') spring-boot:run
exit $LASTEXITCODE
