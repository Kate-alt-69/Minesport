@echo off
setlocal EnableExtensions

where pwsh >nul 2>nul
if not errorlevel 1 goto use_pwsh

goto use_windows_powershell

:use_pwsh
pwsh -NoProfile -ExecutionPolicy Bypass -File "%~dp0check.ps1" %*
exit /b %errorlevel%

:use_windows_powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0check.ps1" %*
exit /b %errorlevel%
