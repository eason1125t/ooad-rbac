# 功能：安装官方WSL并启用Docker必需的Windows组件；需管理员运行，只写安装日志，不自动重启或删除数据。
# 与Docker Desktop的WSL2后端配套；本脚本不修改现有数据库、网络安全或账户权限。
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
$taskReports = Join-Path $taskRoot 'reports/environment'
New-Item -ItemType Directory -Path $taskReports -Force | Out-Null
$taskLog = Join-Path $taskReports 'wsl-admin.log'
$taskResultPath = Join-Path $taskReports 'wsl-admin-result.json'
$taskResult = [ordered]@{status='RUNNING'; restartRequired=$false; features=@(); wslInstallerExitCode=$null; error=$null}
try {
    Write-Host 'Checking administrator privileges...'
    $taskAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
    if(-not $taskAdmin){throw 'Administrator privileges are required'}
    # NoRestart将组件更改留给用户选择重启时机，不能中断当前工作。
    foreach($taskFeatureName in @('VirtualMachinePlatform','Microsoft-Windows-Subsystem-Linux')) {
        Write-Host "Checking Windows feature: $taskFeatureName"
        $taskFeature = Get-WindowsOptionalFeature -Online -FeatureName $taskFeatureName
        if($taskFeature.State -ne 'Enabled') {
            Write-Host 'Enabling feature; Windows servicing can take several minutes...'
            $taskChange = Enable-WindowsOptionalFeature -Online -FeatureName $taskFeatureName -All -NoRestart
            if($taskChange.RestartNeeded){$taskResult.restartRequired=$true}
        }
        $taskResult.features += @{name=$taskFeatureName;state=(Get-WindowsOptionalFeature -Online -FeatureName $taskFeatureName).State.ToString()}
    }
    # WinGet从已核对的Microsoft.WSL精确版本安装并校验官方包哈希，避免自行修改系统文件。
    Write-Host "Installing WSL 2.7.14; detailed output: $taskLog"
    & winget.exe install --id Microsoft.WSL --version 2.7.14 --exact --source winget --silent --accept-source-agreements --accept-package-agreements --disable-interactivity *> $taskLog
    $taskResult.wslInstallerExitCode=$LASTEXITCODE
    if($LASTEXITCODE -notin @(0,3010)){throw "WSL installer exited $LASTEXITCODE"}
    if($LASTEXITCODE -eq 3010){$taskResult.restartRequired=$true}
    $taskResult.status='INSTALLED'
} catch {
    $taskResult.status='FAILED'
    $taskResult.error=$_.Exception.Message
    Write-Warning $taskResult.error
} finally {
    $taskResult | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $taskResultPath -Encoding UTF8
}
Write-Host "WSL setup result: $($taskResult.status)"
Write-Host "Result file: $taskResultPath"
if($taskResult.restartRequired){Write-Host 'A Windows restart is required. This script will NOT restart your computer.'}
if($taskResult.status -ne 'INSTALLED'){exit 1}
