$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$taskProxy = Join-Path $taskRoot 'proxy'
$taskCaptures = Join-Path $taskRoot 'captures'
$taskBinary = Join-Path $taskProxy 'build/page-proxy.exe'
$taskRecord = Join-Path $taskCaptures 'demo-process.json'
New-Item -ItemType Directory -Force -Path $taskCaptures | Out-Null
if (Test-Path -LiteralPath $taskRecord) {
    $taskSaved = Get-Content -Raw -LiteralPath $taskRecord | ConvertFrom-Json
    $taskExisting = Get-Process -Id $taskSaved.pid -ErrorAction SilentlyContinue
    if ($taskExisting -and $taskExisting.Path -eq $taskBinary -and $taskExisting.StartTime.ToUniversalTime().Ticks -eq ([DateTime]$taskSaved.startedUtc).ToUniversalTime().Ticks) {
        Write-Output 'Fixed demo proxy already running on loopback; no OpenAI calls.'
        return
    }
}
$taskProbe = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Parse('127.0.0.1'), 8787)
try { $taskProbe.Start() } catch { throw 'Port 8787 is occupied. No process was stopped.' } finally { $taskProbe.Stop() }
$taskEnvironment = Join-Path $taskProxy '.env.demo'
if (-not (Test-Path -LiteralPath $taskEnvironment)) {
    $taskBytes = [byte[]]::new(32)
    $taskRandom = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $taskRandom.GetBytes($taskBytes) } finally { $taskRandom.Dispose() }
    $taskNewToken = [Convert]::ToBase64String($taskBytes).TrimEnd('=').Replace('+','-').Replace('/','_')
    [System.IO.File]::WriteAllText($taskEnvironment, "DEVICE_TOKEN=$taskNewToken`n", [System.Text.UTF8Encoding]::new($false))
}
$taskTokenMatch = [regex]::Match((Get-Content -Raw -LiteralPath $taskEnvironment), '(?m)^DEVICE_TOKEN=([A-Za-z0-9_-]{32,256})\r?$')
if (-not $taskTokenMatch.Success) { throw 'Invalid demo environment file; token withheld.' }
New-Item -ItemType Directory -Force -Path (Join-Path $taskProxy 'build') | Out-Null
Push-Location $taskProxy
try { go build -o $taskBinary ./cmd/server; if ($LASTEXITCODE -ne 0) { throw 'Proxy build failed' } } finally { Pop-Location }
$env:DEVICE_TOKEN = $taskTokenMatch.Groups[1].Value
$env:DEMO_MODE = '1'
$env:LISTEN_ADDR = '127.0.0.1:8787'
$taskProcess = Start-Process -FilePath $taskBinary -WorkingDirectory $taskProxy -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput (Join-Path $taskCaptures 'proxy-demo.stdout.log') -RedirectStandardError (Join-Path $taskCaptures 'proxy-demo.stderr.log')
Start-Sleep -Milliseconds 400
if ($taskProcess.HasExited) { throw 'Demo proxy failed to start; no credentials were printed.' }
@{ pid=$taskProcess.Id; startedUtc=$taskProcess.StartTime.ToUniversalTime().ToString('O'); mode='fixed-demo' } |
    ConvertTo-Json | Set-Content -LiteralPath $taskRecord -Encoding utf8
Write-Output 'Fixed demo proxy started at 127.0.0.1:8787. No OpenAI calls. Device token is stored only in ignored proxy/.env.demo.'
