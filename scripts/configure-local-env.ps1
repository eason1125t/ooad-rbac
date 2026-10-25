# 功能：为本项目生成一次性本地凭据及Navicat初始化脚本；.env与.tools均忽略提交，不输出秘密。
# Navicat只负责创建空库/开发账号；后续表结构仍由backend中的Flyway迁移维护。
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
$taskEnv = Join-Path $taskRoot '.env'
if(Test-Path -LiteralPath $taskEnv){throw '.env already exists; preserve existing credentials rather than overwrite'}
$taskPrivate = Join-Path $taskRoot '.tools'
New-Item -ItemType Directory -Path $taskPrivate -Force | Out-Null
$taskDbPassword = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24)).ToLowerInvariant()
$taskRootPassword = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24)).ToLowerInvariant()
$taskEnvironment = @"
DB_URL=jdbc:mysql://127.0.0.1:3306/ooad_rbac?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true
DB_USERNAME=ooad_rbac_dev
DB_PASSWORD=$taskDbPassword
MYSQL_ROOT_PASSWORD=$taskRootPassword
REDIS_HOST=127.0.0.1
REDIS_PORT=6380
BOOTSTRAP_ADMIN_USERNAME=
BOOTSTRAP_ADMIN_PASSWORD=
SPRING_PROFILES_ACTIVE=demo
DEMO_RETIRED_USERNAME=
"@
[IO.File]::WriteAllText($taskEnv,$taskEnvironment,[Text.UTF8Encoding]::new($false))
$taskSql = @"
-- 本地Navicat环境初始化；不是业务DDL。只创建专用空库和本机开发账号，禁止修改现有库。
CREATE DATABASE ooad_rbac CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs;
CREATE USER 'ooad_rbac_dev'@'localhost' IDENTIFIED BY '$taskDbPassword';
CREATE USER 'ooad_rbac_dev'@'127.0.0.1' IDENTIFIED BY '$taskDbPassword';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES ON ooad_rbac.* TO 'ooad_rbac_dev'@'localhost';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES ON ooad_rbac.* TO 'ooad_rbac_dev'@'127.0.0.1';
SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='ooad_rbac';
"@
[IO.File]::WriteAllText((Join-Path $taskPrivate 'navicat-local-setup.sql'),$taskSql,[Text.UTF8Encoding]::new($false))
Write-Output 'Private .env and .tools/navicat-local-setup.sql created; no credentials printed'
