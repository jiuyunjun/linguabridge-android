param([Parameter(Mandatory=$true)][string]$Serial, [switch]$Ui)
$ErrorActionPreference = 'Stop'
if ($Ui) {
    $taskPower = (& adb -s $Serial shell dumpsys power 2>&1) -join "`n"
    $taskPolicy = (& adb -s $Serial shell dumpsys window policy 2>&1) -join "`n"
    if ($taskPower -notmatch 'mWakefulness=Awake' -or $taskPolicy -match '(?m)^\s*inputRestricted=true\s*$') {
        throw 'Unlock the device and keep the app visible before UI tests. No unlock or security settings were changed.'
    }
}
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$taskSaved = Get-Content -Raw -LiteralPath (Join-Path $taskRoot 'captures/demo-process.json') | ConvertFrom-Json
$taskProcess = Get-Process -Id $taskSaved.pid -ErrorAction SilentlyContinue
$taskExpected = Join-Path $taskRoot 'proxy/build/page-proxy.exe'
if ($taskSaved.mode -ne 'fixed-demo' -or -not $taskProcess -or $taskProcess.Path -ne $taskExpected -or
    $taskProcess.StartTime.ToUniversalTime().Ticks -ne ([DateTime]$taskSaved.startedUtc).ToUniversalTime().Ticks) {
    throw 'Start the owned fixed demo proxy first; no real OpenAI calls are allowed in this test.'
}
$taskToken = [regex]::Match((Get-Content -Raw -LiteralPath (Join-Path $taskRoot 'proxy/.env.demo')), '(?m)^DEVICE_TOKEN=([A-Za-z0-9_-]{32,256})\r?$').Groups[1].Value
if (-not $taskToken) { throw 'Demo token unavailable; value withheld.' }
$taskClass = if ($Ui) { 'dev.linguabridge.ui.SettingsSmokeTest' } else { 'dev.linguabridge.ui.CoreDeviceTest' }
$taskStart = [System.Diagnostics.ProcessStartInfo]::new()
$taskStart.FileName = (Get-Command adb).Source
$taskStart.UseShellExecute = $false
$taskStart.CreateNoWindow = $true
$taskStart.RedirectStandardOutput = $true
$taskStart.RedirectStandardError = $true
foreach ($taskArgument in @('-s', $Serial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class', $taskClass, '-e', 'demoToken', $taskToken, 'dev.linguabridge.debug.test/androidx.test.runner.AndroidJUnitRunner')) {
    $taskStart.ArgumentList.Add($taskArgument)
}
$taskRunner = [System.Diagnostics.Process]::Start($taskStart)
$taskStdout = $taskRunner.StandardOutput.ReadToEndAsync()
$taskStderr = $taskRunner.StandardError.ReadToEndAsync()
$taskTimedOut = -not $taskRunner.WaitForExit(60000)
if ($taskTimedOut) {
    $taskRunner.Kill()
    & adb -s $Serial shell am force-stop dev.linguabridge.debug | Out-Null
}
$taskRunner.WaitForExit()
$taskExit = $taskRunner.ExitCode
$taskSafe = ($taskStdout.GetAwaiter().GetResult() + "`n" + $taskStderr.GetAwaiter().GetResult()).Replace($taskToken, '[REDACTED]')
if ($taskTimedOut) { $taskSafe += "`nUI_TEST_TIMEOUT: host stopped own test after 60 seconds." }
$taskLog = if ($Ui) { 'captures/device-ui-tests.log' } else { 'captures/device-tests.log' }
[System.IO.File]::WriteAllText((Join-Path $taskRoot $taskLog), $taskSafe, [System.Text.UTF8Encoding]::new($false))
$taskSafe -split "`n" | Where-Object { $_ -match 'INSTRUMENTATION_STATUS: (class|test|current|step)=|INSTRUMENTATION_STATUS_CODE:|^OK \(|^Tests run:|^FAILURES|^INSTRUMENTATION_FAILED|^java\.[^ ]+Exception|^Caused by:|UI_TEST_TIMEOUT|^main:' } | Select-Object -First 50
$taskPassed = [regex]::Matches($taskSafe, '(?m)^INSTRUMENTATION_STATUS_CODE: 0\s*$').Count
if ($taskTimedOut -or $taskExit -ne 0 -or $taskPassed -ne 2 -or $taskSafe -match 'FAILURES!!!|INSTRUMENTATION_FAILED') { throw "Device tests did not both pass. See the scrubbed $taskLog." }
Write-Output 'Device tests: PASS (2 tests, no skipped cases); no real OpenAI calls.'
