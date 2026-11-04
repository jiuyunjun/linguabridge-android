$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$taskRecord = Join-Path $taskRoot 'captures/demo-process.json'
if (-not (Test-Path -LiteralPath $taskRecord)) { Write-Output 'No owned demo process recorded.'; return }
$taskSaved = Get-Content -Raw -LiteralPath $taskRecord | ConvertFrom-Json
$taskProcess = Get-Process -Id $taskSaved.pid -ErrorAction SilentlyContinue
$taskExpected = Join-Path $taskRoot 'proxy/build/page-proxy.exe'
if ($taskProcess -and $taskProcess.Path -eq $taskExpected -and $taskProcess.StartTime.ToUniversalTime().Ticks -eq ([DateTime]$taskSaved.startedUtc).ToUniversalTime().Ticks) {
    Stop-Process -Id $taskProcess.Id
    Write-Output 'Owned demo proxy stopped.'
} else { Write-Output 'No matching owned demo process; no process stopped.' }
