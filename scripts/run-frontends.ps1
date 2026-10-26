# 功能：为三个Vite进程选择可用端口并配置同站点代理；Windows保留5173段时回退15173段，不修改系统端口规则。
param([int]$AdminPort = 5173)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
New-Item -ItemType Directory -Path (Join-Path $taskRoot 'reports') -Force | Out-Null
function Test-TaskPort([int]$Port) {
    $taskListener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $Port)
    try { $taskListener.Start(); return $true } catch { return $false } finally { $taskListener.Stop() }
}
if (-not ((Test-TaskPort $AdminPort) -and (Test-TaskPort ($AdminPort+1)) -and (Test-TaskPort ($AdminPort+2)))) {
    if ($AdminPort -ne 5173) { throw "Requested frontend ports are unavailable: $AdminPort" }
    $AdminPort = 15173
    if (-not ((Test-TaskPort $AdminPort) -and (Test-TaskPort ($AdminPort+1)) -and (Test-TaskPort ($AdminPort+2)))) { throw 'Frontend ports 5173-5175 and 15173-15175 unavailable; choose -AdminPort explicitly.' }
}
$env:OA_DEV_URL = "http://127.0.0.1:$($AdminPort+1)"
$env:PMIS_DEV_URL = "http://127.0.0.1:$($AdminPort+2)"
foreach ($taskApp in @(@('admin',$AdminPort),@('oa',($AdminPort+1)),@('pmis',($AdminPort+2)))) {
    $taskDirectory = Join-Path $taskRoot "frontend/apps/$($taskApp[0])"
    $taskVite = Join-Path $taskRoot 'frontend/node_modules/vite/bin/vite.js'
    $taskProcess = Start-Process node -ArgumentList "`"$taskVite`" --host 127.0.0.1 --port $($taskApp[1]) --strictPort" -WorkingDirectory $taskDirectory -WindowStyle Hidden -RedirectStandardOutput (Join-Path $taskRoot "reports/$($taskApp[0])-dev-out.log") -RedirectStandardError (Join-Path $taskRoot "reports/$($taskApp[0])-dev-error.log") -PassThru
    Write-Output "$($taskApp[0]): PID $($taskProcess.Id), http://127.0.0.1:$($taskApp[1])/$($taskApp[0])/"
}
