@echo off
setlocal EnableExtensions

call "%~dp0build.bat" --check %*
exit /b %errorlevel%
