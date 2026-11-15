$ErrorActionPreference = "Stop"
$DietRoot = Split-Path -Parent $PSScriptRoot
$DietCli = Join-Path $DietRoot ".local-tools/android-sdk/cmdline-tools/latest/bin/android.exe"
$DietSdk = Join-Path $DietRoot ".local-tools/android-sdk"
foreach ($DietPackage in @("platforms;android-37.0", "build-tools;36.0.0", "platform-tools")) {
    & $DietCli --no-metrics "--sdk=$DietSdk" sdk install $DietPackage
    if ($LASTEXITCODE -ne 0) { throw "Android SDK install failed: $DietPackage" }
}
