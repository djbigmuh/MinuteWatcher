# Локальная верификация перед коммитом (замена CI: GitHub не используется).
param([switch]$Quick)

$ErrorActionPreference = "Stop"
$env:ANDROID_PREFS_ROOT = $null

Write-Host "Checking Java version..." -ForegroundColor Cyan
try {
    # We need to capture both stdout and stderr because java -version often writes to stderr
    $javaVersionOutput = & java -version 2>&1 | Out-String
    if ($javaVersionOutput -notmatch '\b21\.') {
        Write-Host "CRITICAL: JDK 21 is required for this project. Current version output:" -ForegroundColor Red
        Write-Host $javaVersionOutput -ForegroundColor Gray
        Write-Host "JDK 25 must not be in PATH. Please set JAVA_HOME to JDK 21." -ForegroundColor Yellow
        exit 1
    }
    Write-Host "Java 21 detected." -ForegroundColor Green
} catch {
    Write-Host "CRITICAL: java command not found. Please ensure JDK 21 is installed and in PATH." -ForegroundColor Red
    exit 1
}

$gradleArgs = if ($Quick) {
    @('ktlintCheck', 'detekt', '--continue')
} else {
    @('ktlintCheck', 'detekt', 'lintDebug', 'testDebugUnitTest', 'assembleDebug', '--continue')
}

Write-Host "Running verification: ./gradlew $($gradleArgs -join ' ')" -ForegroundColor Cyan

# Executing gradle and logging output to verify_output.txt
# Using --continue to ensure all reports are generated even if some tasks fail.
& ./gradlew @gradleArgs 2>&1 | Tee-Object -FilePath verify_output.txt

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
