param([Parameter(ValueFromRemainingArguments=$true)][string[]]$Tasks = @(":app:assembleDebug"))
$ErrorActionPreference = "Stop"
$DietRoot = Split-Path -Parent $PSScriptRoot
$env:JAVA_HOME = Join-Path $DietRoot ".local-tools/jdk/jdk-21.0.13+9"
$env:ANDROID_HOME = Join-Path $DietRoot ".local-tools/android-sdk"
$env:ANDROID_USER_HOME = Join-Path $DietRoot ".local-tools/android-user"
$env:GRADLE_USER_HOME = Join-Path $DietRoot ".local-tools/gradle-user"
$env:PATH = (Join-Path $env:JAVA_HOME "bin") + ";" + $env:PATH
Push-Location $DietRoot
try {
    & (Join-Path $DietRoot "gradlew.bat") @Tasks
    exit $LASTEXITCODE
} finally { Pop-Location }
