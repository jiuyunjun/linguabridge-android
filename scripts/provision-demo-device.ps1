param([Parameter(Mandatory=$true)][string]$Serial)
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$taskMatch = [regex]::Match((Get-Content -Raw -LiteralPath (Join-Path $taskRoot 'proxy/.env.demo')), '(?m)^DEVICE_TOKEN=([A-Za-z0-9_-]{32,256})\r?$')
if (-not $taskMatch.Success) { throw 'Run start-demo.ps1 first. Token withheld.' }
$taskReverse = & adb -s $Serial reverse tcp:8787 tcp:8787 2>&1
if ($LASTEXITCODE -ne 0) { throw 'adb reverse failed' }
$taskToken = $taskMatch.Groups[1].Value
$taskReply = & adb -s $Serial shell am broadcast -n dev.linguabridge.debug/dev.linguabridge.accessibility.DebugInspectReceiver `
    -a dev.linguabridge.debug.CONFIGURE_DEMO_PROXY --es baseUrl http://127.0.0.1:8787 --es deviceToken $taskToken 2>&1
if ($LASTEXITCODE -ne 0) { throw 'Debug demo provisioning failed; output withheld.' }
$taskStatus = (& adb -s $Serial exec-out run-as dev.linguabridge.debug cat files/fixtures/adb-status.txt) -join ''
if ($LASTEXITCODE -ne 0 -or $taskStatus.Trim() -ne 'CONFIGURED') { throw 'App did not confirm demo provisioning.' }
Write-Output 'Demo proxy configured on device. Token encrypted in Android Keystore-backed storage; no page captured or uploaded.'
