param(
    [ValidateSet('status','help','skill','start','update-resources','update-classes-and-resources','restart','task')]
    [string]$Operation = 'status',
    [string]$Project,
    [string]$Configuration,
    [long]$ExecutionId,
    [string]$Caller,
    [ValidateSet("Run","Debug")][string]$Executor = "Debug",
    [string]$TaskId,
    [string]$BaseUrl = 'http://127.0.0.1:17891',
    [string]$ConnectionFile,
    [switch]$Wait,
    [int]$TimeoutSeconds = 300
)
$ErrorActionPreference = 'Stop'
if ($ConnectionFile) { $BaseUrl = (Get-Content -LiteralPath $ConnectionFile -Raw | ConvertFrom-Json).baseUrl }
if ($BaseUrl -notmatch '^http://127\.0\.0\.1:\d+$') { throw '地址必须是本机 HTTP 地址，例如 http://127.0.0.1:17891' }
if ($Operation -eq 'help') { Invoke-RestMethod "$BaseUrl/v1/help" -TimeoutSec 20 | ConvertTo-Json -Depth 12; return }
if ($Operation -eq 'skill') { Invoke-RestMethod "$BaseUrl/v1/skill" -TimeoutSec 20; return }
if ($Operation -eq 'status') {
    Invoke-RestMethod "$BaseUrl/v1/status" -TimeoutSec 20 | ConvertTo-Json -Depth 12
    return
}
if ($Operation -eq 'task') {
    if (!$TaskId -or $TaskId -notmatch '^[a-f0-9-]{36}$') { throw '需要合法的 -TaskId' }
    Invoke-RestMethod "$BaseUrl/v1/tasks/$TaskId" -TimeoutSec 20 | ConvertTo-Json -Depth 12
    return
}
if (!$Project -or !$Configuration -or ($Operation -ne 'start' -and $ExecutionId -le 0) -or !$Caller) {
    throw '更新和重启需要 -Project、-Configuration、-ExecutionId 和 -Caller；先用 status 查询实例 ID'
}
$body = @{ project=$Project; configuration=$Configuration; executionId=$ExecutionId; caller=$Caller; operation=$Operation; executor=$Executor } | ConvertTo-Json
$task = Invoke-RestMethod "$BaseUrl/v1/tasks" -Method Post -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 20
if ($Wait) {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ($task.status -eq 'RUNNING' -and [DateTime]::UtcNow -lt $deadline) {
        Start-Sleep -Milliseconds 500
        $task = Invoke-RestMethod "$BaseUrl/v1/tasks/$($task.id)" -TimeoutSec 20
    }
}
$task | ConvertTo-Json -Depth 12
if ($task.status -eq 'FAILED') { throw "任务失败：$($task.error)" }
if ($Wait -and $task.status -eq 'RUNNING') { throw "等待超时，任务仍在执行；请用 task -TaskId $($task.id) 查询，不要重复提交或自动重启" }
