param([string]$Sdk = "$env:LOCALAPPDATA/Android/Sdk")
$ErrorActionPreference = 'Stop'
$manager = Join-Path $Sdk 'cmdline-tools/latest/bin/avdmanager.bat'
$image = 'system-images;android-35;google_apis;x86_64'
if (!(Test-Path $manager)) { throw 'Install Android SDK Command-line Tools (latest) in Android Studio SDK Manager first.' }
if (!(Test-Path "$Sdk/system-images/android-35/google_apis/x86_64")) {
    throw 'Install API 35 Google APIs x86_64 system image with SDK Manager first; review SDK licenses there.'
}
# No --force: never overwrite an existing virtual device.
'no' | & $manager create avd -n CMTS_API35 -k $image
if ($LASTEXITCODE -ne 0) { throw 'AVD creation failed (possibly already exists); inspect Device Manager.' }
Write-Output 'Created CMTS_API35. Start it from Android Studio Device Manager.'
