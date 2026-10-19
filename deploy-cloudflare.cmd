@echo off
setlocal
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0deploy-cloudflare.ps1" %*
set "result=%errorlevel%"
if not "%result%"=="0" echo Deployment failed. Review the error above and the logs folder.
pause
exit /b %result%
