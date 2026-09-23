# Local pre-commit verification (CI replacement: GitHub is not used).
param([switch]$Quick)

$ErrorActionPreference = "Stop"
$env:ANDROID_PREFS_ROOT = $null

function Get-JavaVersion {
    param([string]$JavaPath)
    $prevEAP = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = (& cmd /c "`"$JavaPath`" -version 2>&1") | Out-String
        $exitCode = $LASTEXITCODE
        return [PSCustomObject]@{
            Output   = $output
            ExitCode = $exitCode
        }
    } finally {
        $ErrorActionPreference = $prevEAP
    }
}

Write-Host "Checking Java version..." -ForegroundColor Cyan

# 1. Check java in PATH
$pathJava = Get-JavaVersion "java"
if ($pathJava.ExitCode -ne 0) {
    Write-Host "CRITICAL: java command not found. Please ensure JDK 21 is installed and in PATH." -ForegroundColor Red
    exit 1
}
if ($pathJava.Output -notmatch '\b21\.') {
    Write-Host "CRITICAL: JDK 21 is required in PATH. Current version output:" -ForegroundColor Red
    Write-Host $pathJava.Output -ForegroundColor Gray
    exit 1
}

# 2. Check JAVA_HOME if set
if ($env:JAVA_HOME) {
    $javaHomeJavaPath = Join-Path $env:JAVA_HOME "bin\java.exe"
    if (-not (Test-Path $javaHomeJavaPath)) {
        Write-Host "CRITICAL: JAVA_HOME is set but java executable not found at: $javaHomeJavaPath" -ForegroundColor Red
        exit 1
    }

    $javaHomeJava = Get-JavaVersion $javaHomeJavaPath
    if ($javaHomeJava.ExitCode -ne 0 -or $javaHomeJava.Output -notmatch '\b21\.') {
        Write-Host "CRITICAL: JAVA_HOME points to a non-JDK 21 version. Location: $env:JAVA_HOME" -ForegroundColor Red
        Write-Host $javaHomeJava.Output -ForegroundColor Gray
        exit 1
    }
    Write-Host "Java 21 detected (PATH and JAVA_HOME)." -ForegroundColor Green
} else {
    Write-Host "Java 21 detected in PATH." -ForegroundColor Green
}

$gradleArgs = if ($Quick) {
    @('ktlintCheck', 'detekt', '--continue')
} else {
    @('ktlintCheck', 'detekt', 'lintDebug', 'testDebugUnitTest', 'assembleDebug', 'assembleRelease', '--continue')
}

Write-Host "Running verification: ./gradlew $($gradleArgs -join ' ')" -ForegroundColor Cyan

# Executing gradle and logging output to verify_output.txt
# Using --continue to ensure all reports are generated even if some tasks fail.
# Using cmd /c to avoid NativeCommandError in PowerShell 5.1 when redirection 2>&1 is used.
$gradleCmd = ".\gradlew.bat $($gradleArgs -join ' ')"
& cmd /c "$gradleCmd 2>&1" | Tee-Object -FilePath verify_output.txt

$exitCode = $LASTEXITCODE
$timestamp = Get-Date -Format 'yyyy-MM-dd HH:mm:ss'

if ($exitCode -eq 0) {
    "OK $timestamp $exitCode" | Set-Content -Path verify_status.txt -Encoding UTF8
    Write-Host "VERIFY OK" -ForegroundColor Green
} else {
    "FAILED $timestamp $exitCode" | Set-Content -Path verify_status.txt -Encoding UTF8
    Write-Host "VERIFY FAILED (exit code: $exitCode). Check verify_output.txt for details." -ForegroundColor Red
    exit $exitCode
}
