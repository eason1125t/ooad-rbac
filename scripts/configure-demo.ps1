# 功能：将本项目本机开发环境切换为显式demo；保留数据库/MCP凭据并备份原.env，不触碰其他项目。
# DemoAccountsInitializer读取账号目录；旧引导账号仅按显式配置停用，保留历史不删除。
$ErrorActionPreference='Stop'
$taskRoot=Split-Path $PSScriptRoot -Parent
$taskEnv=Join-Path $taskRoot '.env'
$taskLines=@(Get-Content -LiteralPath $taskEnv)
$taskValues=@{}
foreach($taskLine in $taskLines){if($taskLine-match'^([A-Z_]+)=(.*)$'){$taskValues[$Matches[1]]=$Matches[2]}}
if($taskValues.DB_URL -notmatch '^jdbc:mysql://(127\.0\.0\.1|localhost):3306/ooad_rbac\?'){throw 'Demo setup is limited to this project local development database'}
$taskBackup=Join-Path $taskRoot '.tools/env-before-demo'
if(-not(Test-Path -LiteralPath $taskBackup)){Copy-Item -LiteralPath $taskEnv -Destination $taskBackup}
$taskRetired=$taskValues.DEMO_RETIRED_USERNAME
if(-not $taskRetired -and $taskValues.BOOTSTRAP_ADMIN_USERNAME -and $taskValues.BOOTSTRAP_ADMIN_USERNAME-ne'super-admin'){$taskRetired=$taskValues.BOOTSTRAP_ADMIN_USERNAME}
$taskChanges=@{SPRING_PROFILES_ACTIVE='demo';BOOTSTRAP_ADMIN_USERNAME='';BOOTSTRAP_ADMIN_PASSWORD='';DEMO_RETIRED_USERNAME=$taskRetired}
$taskOutput=[Collections.Generic.List[string]]::new()
foreach($taskLine in $taskLines){if($taskLine-match'^([A-Z_]+)='){$taskKey=$Matches[1];if($taskChanges.ContainsKey($taskKey)){continue}};$taskOutput.Add($taskLine)}
foreach($taskKey in @('SPRING_PROFILES_ACTIVE','BOOTSTRAP_ADMIN_USERNAME','BOOTSTRAP_ADMIN_PASSWORD','DEMO_RETIRED_USERNAME')){$taskOutput.Add("$taskKey=$($taskChanges[$taskKey])")}
[IO.File]::WriteAllLines($taskEnv,$taskOutput,[Text.UTF8Encoding]::new($false))
Write-Output 'Local demo profile configured; existing infrastructure credentials preserved; original .env backup retained privately.'
