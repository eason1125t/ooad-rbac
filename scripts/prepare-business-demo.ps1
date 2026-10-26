# 功能：仅在本机显式demo准备合成业务人员、目录、角色和初始授权；全部调用真实API，不重置已有账号或恢复撤销关系。
# 关联：先运行run-backend.ps1；按05测试指南使用OA/PMIS页面，1234只用于本脚本的隔离演示账号。
param([string]$BaseUrl='http://127.0.0.1:8080')
$ErrorActionPreference='Stop'
$endpoint=[Uri]$BaseUrl
if($endpoint.Scheme -notin @('http','https') -or $endpoint.Host -notin @('127.0.0.1','localhost','::1')){throw '仅允许本机回环地址的隔离演示环境。'}
$projectDirectory=Split-Path $PSScriptRoot -Parent
$environmentFile=Join-Path $projectDirectory '.env'
if(-not (Test-Path -LiteralPath $environmentFile)){throw '先按04配置本地.env并启用demo。'}
$profiles=@(Get-Content -LiteralPath $environmentFile | Where-Object {$_ -match '^SPRING_PROFILES_ACTIVE='} | ForEach-Object {($_ -split '=',2)[1].Trim().Trim('"',"'")}) -join ','
if($profiles -notmatch '(^|,)demo(,|$)' -or $profiles -match '(^|,)prod(,|$)'){throw '本脚本只支持显式demo且不能与prod组合。'}
$script:demoSession=New-Object Microsoft.PowerShell.Commands.WebRequestSession
function Read-Api([string]$Path){return (Invoke-RestMethod -Uri "$BaseUrl/api/v1$Path" -WebSession $script:demoSession).data}
function Write-Api([string]$Method,[string]$Path,[object]$Body){
    $csrf=Read-Api '/auth/csrf'
    $headers=@{}; $headers[$csrf.headerName]=$csrf.token
    return (Invoke-RestMethod -Uri "$BaseUrl/api/v1$Path" -Method $Method -WebSession $script:demoSession -Headers $headers -ContentType 'application/json' -Body ($Body | ConvertTo-Json -Depth 20)).data
}
$csrf=Read-Api '/auth/csrf';$loginHeaders=@{};$loginHeaders[$csrf.headerName]=$csrf.token
$null=Invoke-RestMethod -Uri "$BaseUrl/api/v1/auth/login" -Method POST -WebSession $script:demoSession -Headers $loginHeaders -ContentType 'application/x-www-form-urlencoded' -Body @{username='super-admin';password='1234'}
$me=Read-Api '/auth/me'
if($me.forcePasswordChange){throw '当前不是可用的演示平台账号。'}
$organizations=@((Read-Api '/admin/organizations').items)
$root=@($organizations | Where-Object {-not $_.parentId})
if($root.Count -eq 0){$root=@(Write-Api POST '/admin/organizations' @{code='demo-company';name='演示总公司';nodeType='HEADQUARTERS';parentId=$null})}
if($root.Count -ne 1 -or -not $root[0].enabled){throw '需要一个启用的总公司根，请先在页面核对组织。'}
$department=@($organizations | Where-Object {$_.code -eq 'demo-business'})
if($department.Count -eq 0){$department=@(Write-Api POST '/admin/organizations' @{code='demo-business';name='业务演示部';nodeType='DEPARTMENT';parentId=$root[0].id})}
if($department[0].parentId -ne $root[0].id -or -not $department[0].enabled){throw '已有demo-business组织与预期不同，请在页面核对，不会覆盖。'}
$report=[System.Collections.Generic.List[object]]::new()
foreach($application in @('oa','pmis')){
    $apps=@((Read-Api '/admin/catalog/applications').items)
    $app=@($apps | Where-Object {$_.code -eq $application})[0]
    if(-not $app.enabled){throw "请先在页面启用$application，并确认恢复影响。"}
    $path="/admin/applications/$($app.id)"
    $catalog=Read-Api "$path/catalog"
    $resourceCode=if($application -eq 'oa'){'document'}else{'project'}
    $resource=@($catalog.resources | Where-Object {$_.code -eq $resourceCode -and $_.kind -eq 'FUNCTION'})[0]
    if(-not $resource){throw "缺少$resourceCode功能节点，请先在目录创建。"}
    $ending=if($application -eq 'oa'){'void'}else{'cancel'}
    foreach($action in @('view','create','update','submit','withdraw','approve','reject',$ending)){
        $code="${application}:${resourceCode}:$action"
        if(-not @($catalog.permissions | Where-Object {$_.code -eq $code}).Count){
            $null=Write-Api POST "$path/permissions" @{resourceId=$resource.id;action=$action;name="$application $action";description='合成业务测试动作'}
        }
    }
    $catalog=Read-Api "$path/catalog"
    foreach($kind in @('employee','approver')){
        $username="$application-$kind";$roleCode="demo-$application-$kind"
        $existing=@((Read-Api "/admin/accounts?q=$username&page=1&size=20").items | Where-Object {$_.username -eq $username})
        if($existing.Count){$report.Add([pscustomobject]@{账号=$username;结果='已存在，保留口令、启停与现有授权'});continue}
        $person=@((Read-Api "/admin/employees?q=$username&page=1&size=20").items | Where-Object {$_.employeeCode -eq $username})
        if(-not $person.Count){$person=@(Write-Api POST '/admin/employees' @{employeeCode=$username;displayName="$username 演示人员";organizationId=$department[0].id;positionName='合成测试岗位';phone=$null})}
        $credential=Write-Api POST '/admin/accounts' @{username=$username;employeeId=$person[0].id}
        if($credential.account.forcePasswordChange -or $credential.temporaryPassword -ne '1234'){throw '服务器不是预期demo策略；已创建账号请在平台核对，本脚本不继续授权。'}
        $account=$credential.account;$credential=$null
        $roles=@((Read-Api "$path/roles?q=$roleCode&page=1&size=20").items | Where-Object {$_.code -eq $roleCode})
        if($roles.Count){$report.Add([pscustomobject]@{账号=$username;结果='已建号；角色已存在，需在页面核对并显式授予'});continue}
        $role=Write-Api POST "$path/roles" @{code=$roleCode;name="$username 测试角色";kind='COMMON';ownerUserId=$null;personnelScope=@{allCompany=$true;organizations=@()}}
        $selectedActions=if($kind -eq 'employee'){@('view','create','update','submit','withdraw',$ending)}else{@('view','approve','reject')}
        $scope=if($kind -eq 'employee'){@{kind='SELF';organizationIds=@()}}else{@{kind='ORG_SUBTREE';organizationIds=@($department[0].id)}}
        $grants=@(foreach($action in $selectedActions){
            $code="${application}:${resourceCode}:$action";$p=@($catalog.permissions | Where-Object {$_.code -eq $code})[0]
            if(-not $p.effectiveEnabled -or $p.implementationStatus -ne 'IMPLEMENTED'){throw "$code 当前不可授予，请先在页面核对目录，不会自动启用。"}
            @{permissionId=$p.id;scope=$scope;validFrom=$null;validTo=$null}
        })
        $definition=Read-Api "$path/roles/$($role.id)/permissions"
        $preview=Write-Api POST "$path/roles/$($role.id)/permissions/preview" @{expectedVersion=$definition.version;permissions=$grants}
        $null=Write-Api PUT "$path/roles/$($role.id)/permissions" @{previewToken=$preview.token}
        $current=Read-Api "$path/users/$($account.id)/roles"
        $preview=Write-Api POST "$path/users/$($account.id)/roles/preview" @{expectedVersion=$current.version;memberships=@(@{roleId=$role.id;validFrom=$null;validTo=$null})}
        $null=Write-Api PUT "$path/users/$($account.id)/roles" @{previewToken=$preview.token}
        $report.Add([pscustomobject]@{账号=$username;结果='新建合成人员、账号和角色，已显式完成初始授权'})
    }
}
$null=Write-Api POST '/auth/logout' @{}
$report | Format-Table -AutoSize
Write-Host '准备完成。新建演示账号使用1234；既有账号和已撤销权限保持原样。'
