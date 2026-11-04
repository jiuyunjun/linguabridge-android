param(
    [string]$AndroidSdk = $(if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android/Sdk' })
)
$ErrorActionPreference = 'Stop'
$taskAapt = Join-Path $AndroidSdk 'build-tools/35.0.0/aapt2.exe'
$taskSigner = Join-Path $AndroidSdk 'build-tools/35.0.0/apksigner.bat'
$taskBuild = Join-Path $PSScriptRoot '../android/app/build/outputs/apk'
foreach ($taskVariant in @('debug', 'release')) {
    $taskName = if ($taskVariant -eq 'debug') { 'app-debug.apk' } else { 'app-release-unsigned.apk' }
    $taskApk = (Resolve-Path (Join-Path $taskBuild "$taskVariant/$taskName")).Path
    $taskManifest = & $taskAapt dump xmltree $taskApk --file AndroidManifest.xml 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) { throw "Cannot inspect $taskVariant APK" }
    if ($taskManifest -match 'android.permission.SYSTEM_ALERT_WINDOW') {
        throw "Unexpected overlay permission in $taskVariant APK"
    }
    $taskZip = [System.IO.Compression.ZipFile]::OpenRead($taskApk)
    try {
        $taskInspectorResource = $taskZip.GetEntry('res/xml/inspector_service.xml')
        if ($taskVariant -eq 'debug') {
            if ($taskManifest -notmatch 'BIND_ACCESSIBILITY_SERVICE' -or $null -eq $taskInspectorResource -or $taskManifest -notmatch 'android.permission.INTERNET' -or
                $taskManifest -notmatch 'DebugInspectReceiver' -or $taskManifest -notmatch 'android.permission.DUMP') {
                throw 'Debug inspector or its system binding permission missing'
            }
            $taskServiceXml = & $taskAapt dump xmltree $taskApk --file res/xml/inspector_service.xml 2>&1 | Out-String
            if ($LASTEXITCODE -ne 0 -or $taskServiceXml -match 'jp\.naver\.line\.android|com\.tencent\.mm' -or $taskServiceXml -match 'packageNames') {
                throw 'Debug service must follow the foreground app without a preset package list'
            }
            $taskNetworkXml = & $taskAapt dump xmltree $taskApk --file res/xml/debug_network_security.xml 2>&1 | Out-String
            if ($LASTEXITCODE -ne 0 -or $taskNetworkXml -notmatch '127\.0\.0\.1' -or $taskNetworkXml -notmatch 'localhost') { throw 'Debug loopback network configuration missing' }
            & $taskSigner verify $taskApk
            if ($LASTEXITCODE -ne 0) { throw 'Debug signature verification failed' }
        } else {
            if ($taskManifest -match 'InspectorService|InspectorActivity|DebugInspectReceiver|BIND_ACCESSIBILITY_SERVICE|android.permission.INTERNET' -or $null -ne $taskInspectorResource) {
                throw 'Release contains a debug inspector declaration/resource'
            }
            foreach ($taskEntry in $taskZip.Entries | Where-Object { $_.FullName -match '^classes\d*\.dex$' }) {
                $taskStream = $taskEntry.Open()
                $taskBuffer = [System.IO.MemoryStream]::new()
                try {
                    $taskStream.CopyTo($taskBuffer)
                    $taskDex = [System.Text.Encoding]::UTF8.GetString($taskBuffer.ToArray())
                    if ($taskDex -match 'InspectorService|InspectorActivity|FixtureExporter|DebugInspectReceiver') {
                        throw 'Release DEX contains debug code'
                    }
                } finally { $taskStream.Dispose(); $taskBuffer.Dispose() }
            }
        }
    } finally { $taskZip.Dispose() }
    Write-Output "$taskVariant packaging: PASS"
    Get-FileHash -LiteralPath $taskApk -Algorithm SHA256 | Select-Object Hash, Path
}
