param([string]$Serial, [ValidateSet('emulator','phone','rk3588')][string]$DeviceClass = 'emulator', [string]$JavaHome = 'C:/Program Files/Android/Android Studio/jbr', [string]$ProxyHost, [int]$ProxyPort = 7897)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$evidence = Join-Path $root "evidence/$stamp-$DeviceClass"
New-Item -ItemType Directory -Force $evidence | Out-Null
Start-Transcript -Path "$evidence/build-and-device.log"
$oldJava = $env:JAVA_HOME
$oldOptions = $env:JAVA_TOOL_OPTIONS
try {
    if (Test-Path "$JavaHome/bin/java.exe") { $env:JAVA_HOME = $JavaHome }
    if ($ProxyHost) { $env:JAVA_TOOL_OPTIONS = "$oldOptions -Dhttp.proxyHost=$ProxyHost -Dhttp.proxyPort=$ProxyPort -Dhttps.proxyHost=$ProxyHost -Dhttps.proxyPort=$ProxyPort" }
    Push-Location $root
    try {
        $gradle = Get-Command gradle -ErrorAction SilentlyContinue
        if (Test-Path './gradlew.bat') { $runner = './gradlew.bat' }
        elseif ($gradle) { $runner = $gradle.Source }
        else { throw 'Gradle Wrapper missing; restore project wrapper files.' }
        & $runner --no-daemon --console=plain testDebugUnitTest lintDebug assembleDebug
        if ($LASTEXITCODE -ne 0) { throw 'Build/test/lint failed' }
        Copy-Item './app/build/outputs/apk/debug/app-debug.apk' $evidence
        Copy-Item './app/build/reports' "$evidence/reports" -Recurse
        if ($Serial) {
            $sdk = $env:ANDROID_HOME
            if (!$sdk) { $sdk = "$env:LOCALAPPDATA/Android/Sdk" }
            $adb = Join-Path $sdk 'platform-tools/adb.exe'
            & $adb -s $Serial shell getprop
            if ($LASTEXITCODE -ne 0) { throw 'Device unavailable or unauthorized' }
            & $adb -s $Serial install -r './app/build/outputs/apk/debug/app-debug.apk'
            if ($LASTEXITCODE -ne 0) { throw 'Install failed' }
            & $adb -s $Serial shell am start -W -n com.cmts.mobile/.MainActivity
            if ($LASTEXITCODE -ne 0) { throw 'Launch failed' }
            & $adb -s $Serial logcat -d -t 500 | Out-File "$evidence/logcat.txt"
            'Install/launch commands executed; interactive acquisition acceptance remains REQUIRED.' | Out-File "$evidence/verification.txt"
        }
    } finally { Pop-Location }
} catch {
    $_.Exception.Message | Out-File "$evidence/failure.txt"
    throw
} finally { $env:JAVA_HOME = $oldJava; $env:JAVA_TOOL_OPTIONS = $oldOptions; Stop-Transcript }
