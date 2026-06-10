@echo off
cd /d %~dp0

rem Re-invoke once through dotenv-cli so SUPABASE_PROD_* vars come from .env.production
if "%_DOTENV_WRAPPED%"=="" (
    set _DOTENV_WRAPPED=1
    call dotenv -e .env.production -- cmd /c "%~f0" %*
    exit /b %errorlevel%
)

set YES=0
set FILE=

:parse
if "%~1"=="" goto parsed
if /i "%~1"=="-y" (
    set YES=1
) else if /i "%~1"=="--yes" (
    set YES=1
) else (
    set FILE=%~1
)
shift
goto parse
:parsed

if "%FILE%"=="" (
    for /f "delims=" %%f in ('dir /b /o:n backups\prod-data-*.sql 2^>nul') do set FILE=backups\%%f
)
if "%FILE%"=="" (
    echo ERROR: no backups found in backups\ - run backup-prod-db first
    exit /b 1
)

if not exist "%FILE%" (
    echo ERROR: backup file not found: %FILE%
    exit /b 1
)
for %%F in ("%FILE%") do if %%~zF==0 (
    echo ERROR: backup file is empty: %FILE%
    exit /b 1
)

if "%SUPABASE_PROD_DB_HOST%"=="" (
    echo ERROR: SUPABASE_PROD_DB_HOST not set - populate .env.production
    exit /b 1
)
if "%SUPABASE_PROD_DB_USERNAME%"=="" (
    echo ERROR: SUPABASE_PROD_DB_USERNAME not set - populate .env.production
    exit /b 1
)
if "%SUPABASE_PROD_DB_PASSWORD%"=="" (
    echo ERROR: SUPABASE_PROD_DB_PASSWORD not set - populate .env.production
    exit /b 1
)

docker info >nul 2>&1
if errorlevel 1 (
    echo ERROR: Docker is not running - needed for psql
    exit /b 1
)

echo About to WIPE prod app data (suite_user, conversation, message, user_role)
echo and restore from: %FILE%
if "%YES%"=="1" goto confirmed
set /p ANSWER=Type 'yes' to continue:
if /i not "%ANSWER%"=="yes" (
    echo Aborted.
    exit /b 1
)
:confirmed

set PGPASSWORD=%SUPABASE_PROD_DB_PASSWORD%

(
    echo TRUNCATE suite_user, conversation, message, user_role RESTART IDENTITY CASCADE;
    type "%FILE%"
) | docker run --rm -i -e PGPASSWORD postgres:17 psql -h "%SUPABASE_PROD_DB_HOST%" -U "%SUPABASE_PROD_DB_USERNAME%" -d postgres --single-transaction -v ON_ERROR_STOP=1 -q
if errorlevel 1 (
    echo ERROR: restore failed - transaction rolled back, prod data unchanged
    exit /b 1
)

echo Restore complete. Row counts:
docker run --rm -e PGPASSWORD postgres:17 psql -h "%SUPABASE_PROD_DB_HOST%" -U "%SUPABASE_PROD_DB_USERNAME%" -d postgres -t -A -c "SELECT 'suite_user:   ' || count(*) FROM suite_user UNION ALL SELECT 'conversation: ' || count(*) FROM conversation UNION ALL SELECT 'message:      ' || count(*) FROM message UNION ALL SELECT 'user_role:    ' || count(*) FROM user_role;"
