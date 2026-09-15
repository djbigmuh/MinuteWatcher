@echo off
setlocal
cd /d "%~dp0"
chcp 65001 >nul

echo.
echo ============================================
echo MinuteWatcher one-click audit dumper
echo ============================================
echo.
echo Project folder:
echo %CD%
echo.
echo Starting...
echo.

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0make_audit_dump.ps1" %*
set "EXITCODE=%ERRORLEVEL%"

echo.
echo ============================================
echo Done. Exit code: %EXITCODE%
echo ============================================
echo.
pause
