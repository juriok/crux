# Build Crux with the portable toolchain in %USERPROFILE%\android-tools.
#
#   .\build.ps1                 # assembleRelease
#   .\build.ps1 assembleDebug
#   .\build.ps1 clean assembleRelease
#
$ErrorActionPreference = "Stop"

$tools = Join-Path $env:USERPROFILE "android-tools"
$env:JAVA_HOME = Join-Path $tools "jdk-17.0.20+8"
$env:ANDROID_HOME = Join-Path $tools "sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$gradle = Join-Path $tools "gradle-8.11.1\bin\gradle.bat"

Push-Location $PSScriptRoot
try {
    $tasks = if ($args.Count -gt 0) { $args } else { @("assembleRelease") }
    & $gradle @tasks
    if ($LASTEXITCODE -ne 0) { throw "gradle exited with $LASTEXITCODE" }
} finally {
    Pop-Location
}
