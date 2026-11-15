param(
    [Parameter(Mandatory = $true)][ValidatePattern('^[a-zA-Z0-9_.:-]+$')][string]$Serial,
    [ValidateRange(1024, 65535)][int]$ServerPort = 15037,
    [ValidatePattern('^[a-zA-Z0-9_.,#]+$')][string]$Classes
)
$ErrorActionPreference = "Stop"
$DietRoot = Split-Path -Parent $PSScriptRoot
$DietAdb = Join-Path $DietRoot ".local-tools/android-sdk/platform-tools/adb.exe"
$DietDebug = Join-Path $DietRoot "app/build/outputs/apk/debug/app-debug.apk"
$DietTests = Join-Path $DietRoot "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
foreach ($DietFile in @($DietAdb, $DietDebug, $DietTests)) {
    if (-not (Test-Path -LiteralPath $DietFile)) { throw "Missing: $DietFile" }
}
$DietAdbArgs = @("-P", "$ServerPort", "-s", $Serial)
& $DietAdb @DietAdbArgs get-state
if ($LASTEXITCODE -ne 0) { throw "The explicitly selected test device is unavailable" }
# Deliberately no uninstall/clear-data, no implicit physical-device selection.
& $DietAdb @DietAdbArgs install -r $DietDebug
if ($LASTEXITCODE -ne 0) { throw "Debug install failed; existing app was preserved" }
& $DietAdb @DietAdbArgs install -r $DietTests
if ($LASTEXITCODE -ne 0) { throw "Instrumentation install failed" }
$DietLogDirectory = Join-Path $DietRoot ".local-tools/test-results"
New-Item -ItemType Directory -Force -Path $DietLogDirectory | Out-Null
$DietLog = Join-Path $DietLogDirectory ("android-" + (Get-Date -Format "yyyyMMdd-HHmmss") + ".log")
$DietInstrument = @("shell", "am", "instrument", "-w", "-r")
if ($Classes) { $DietInstrument += @("-e", "class", $Classes) }
$DietInstrument += "app.diet.test/androidx.test.runner.AndroidJUnitRunner"
& $DietAdb @DietAdbArgs @DietInstrument *> $DietLog
$DietRunnerExit = $LASTEXITCODE
Get-Content -LiteralPath $DietLog -Tail 80
$DietText = Get-Content -LiteralPath $DietLog -Raw
if ($DietRunnerExit -ne 0 -or $DietText -notmatch 'OK \([1-9][0-9]* tests?\)' -or $DietText -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed') {
    throw "Android tests did not pass. Log: $DietLog"
}
Write-Output "Verified Android result: $DietLog"
