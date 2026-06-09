@echo off
cd /d %~dp0

echo Killing prod servers...
call npx --yes kill-port 8091 5176

echo Copying JAR to release\...
if not exist release mkdir release
del /q release\agent-suite-*.jar 2>nul
xcopy /y "target\agent-suite-*.jar" "release\" >nul 2>&1
if errorlevel 1 (
    echo ERROR: No JAR found in target\ - run build.cmd first
    exit /b 1
)
echo JAR copied.

echo Building frontend for prod...
cd frontend
call npm run build:prod
if errorlevel 1 exit /b 1
cd ..

echo Restarting prod servers...
powershell.exe -File "C:\Users\Lenovo\start-agent-suite-prod.ps1"
