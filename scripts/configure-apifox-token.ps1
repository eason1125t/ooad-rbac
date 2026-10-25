# 功能：用户在本机交互配置Apifox令牌，供项目.codex/config.toml使用；不打印、不写仓库或聊天。
# 需先由用户在Apifox账号设置创建有有效期的令牌；它继承账号权限。完成后重启Codex载入环境变量。
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
$taskSecret = Read-Host 'Paste Apifox access token (hidden input)' -AsSecureString
$taskCredential = [Management.Automation.PSCredential]::new('apifox',$taskSecret)
$taskToken = $taskCredential.GetNetworkCredential().Password
if([string]::IsNullOrWhiteSpace($taskToken)){throw 'Empty token; nothing configured'}
[Environment]::SetEnvironmentVariable('APIFOX_ACCESS_TOKEN',$taskToken,'User')
[Environment]::SetEnvironmentVariable('APIFOX_ACCESS_TOKEN',$taskToken,'Process')
$taskConfig = Join-Path $taskRoot '.codex/config.toml'
$taskText = [IO.File]::ReadAllText($taskConfig)
if($taskText -notmatch '\[mcp_servers\.apifox\]'){throw 'Expected project Apifox MCP configuration'}
$taskText = $taskText.Replace('enabled = false','enabled = true')
[IO.File]::WriteAllText($taskConfig,$taskText,[Text.UTF8Encoding]::new($false))
$taskToken = $null
Write-Output 'Apifox token stored in your local user environment; MCP enabled. Restart Codex, then verify project 8967444 is accessible.'
