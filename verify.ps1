# Локальная верификация перед коммитом (замена CI: GitHub не используется).
param([switch]$Quick)
$ErrorActionPreference = "Stop"
$env:ANDROID_PREFS_ROOT = $null
if ($Quick) {
    ./gradlew ktlintCheck detekt
} else {
    ./gradlew ktlintCheck detekt lintDebug testDebugUnitTest assembleDebug
}
if ($LASTEXITCODE -ne 0) { Write-Host "VERIFY FAILED" -ForegroundColor Red; exit 1 }
Write-Host "VERIFY OK" -ForegroundColor Green
