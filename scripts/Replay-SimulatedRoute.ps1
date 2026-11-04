param([Parameter(Mandatory)][string]$Serial, [string]$Sdk = "$env:LOCALAPPDATA/Android/Sdk")
$ErrorActionPreference = 'Stop'
if ($Serial -notmatch '^emulator-[0-9]+$') { throw 'This SIMULATED route is only permitted on an emulator serial.' }
$adb = Join-Path $Sdk 'platform-tools/adb.exe'
Write-Output 'SIMULATED GNSS fixture. Never use as real hardware acceptance evidence.'
foreach ($longitude in @('121.47000','121.47010','121.47020','121.47030','121.47040')) {
    & $adb -s $Serial emu geo fix $longitude 31.23000 10
    if ($LASTEXITCODE -ne 0) { throw 'Emulator location injection failed' }
    Start-Sleep -Seconds 1
}
