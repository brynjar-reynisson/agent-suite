@echo off
cd /d %~dp0

rem Re-invoke once through dotenv-cli so SUPABASE_PROD_* vars come from .env.production
if "%_DOTENV_WRAPPED%"=="" (
    set _DOTENV_WRAPPED=1
    call dotenv -e .env.production -- cmd /c "%~f0" %*
    exit /b %errorlevel%
)

if not exist backups mkdir backups

for /f %%i in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmmss"') do set TS=%%i
set OUT=backups\prod-data-%TS%.sql

echo Dumping prod data to %OUT% ...
call npx --yes supabase db dump --linked --data-only --use-copy -f "%OUT%"
if errorlevel 1 (
    echo ERROR: supabase db dump failed
    if exist "%OUT%" del /q "%OUT%"
    exit /b 1
)

if not exist "%OUT%" (
    echo ERROR: dump produced no file: %OUT%
    exit /b 1
)
for %%F in ("%OUT%") do (
    if %%~zF==0 (
        echo ERROR: dump produced empty file: %OUT%
        del /q "%OUT%"
        exit /b 1
    )
    echo Backup written: %OUT% ^(%%~zF bytes^)
)
